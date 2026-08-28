"""talk 工具组内交流限制测试.

覆盖:
  - 同组成员可 talk (普通消息 + wait 同步等待)
  - 跨组成员 talk 被拒绝, 错误提示引导使用邮件 (send_email)
  - 未分组角色不受限制 (兼容旧调用/招聘新人)
  - 花名册显示所属分组
"""

from __future__ import annotations

import threading
import time

from src.core.roles import AgentRole, RolePool
from src.python_tools import talk_toolkit
from src.python_tools.talk_toolkit import create_talk_toolkit


def _setup_roles(tmp_path, monkeypatch, *roles):
    """构造角色 + 各自的 talk 工具类, 返回 (pool, {rid: toolkit})."""
    monkeypatch.setattr("src.core.roles.JOURNAL_DIR", tmp_path)
    pool = RolePool()
    toolkits = {}
    for role in roles:
        pool.add_role(role)
        role._pool = pool  # 模拟 start() 后的 back-reference
        tk = create_talk_toolkit(pool)
        tk.bind("role", role)
        toolkits[role.role_id] = tk
    return pool, toolkits


def _talk(toolkits, sender_id: str, target: str, message: str, wait=False) -> str:
    """调用发送方 talk 处理器 (与 call_tool 同一逻辑)."""
    args: dict[str, object] = {"target": target, "message": message}
    if wait:
        args["wait"] = True
    return toolkits[sender_id]._tools["talk"].handler(args)


def _wait_until(pred, timeout=5.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if pred():
            return True
        time.sleep(0.02)
    return False


def _role(name, rid, group):
    return AgentRole(name=name, role_id=rid, group=group)


# ── 同组放行 ───────────────────────────────────────────────

def test_same_group_allowed(tmp_path, monkeypatch):
    """同组成员之间 talk 正常送达."""
    pool, tks = _setup_roles(
        tmp_path, monkeypatch,
        _role("顾承宇", "frontend_dev_1", "前端开发组"),
        _role("陈思远", "frontend_lead", "前端开发组"),
    )
    result = _talk(tks, "frontend_dev_1", "陈思远", "组件重构完成")
    assert "消息已发送给 陈思远" in result
    assert pool.get_role("frontend_lead").queue_depth == 1


def test_same_group_wait_roundtrip(tmp_path, monkeypatch):
    """同组成员 wait=true 往返正常 (与旧 wait 语义一致)."""
    pool, tks = _setup_roles(
        tmp_path, monkeypatch,
        _role("顾承宇", "frontend_dev_1", "前端开发组"),
        _role("陈思远", "frontend_lead", "前端开发组"),
    )
    role_a = pool.get_role("frontend_dev_1")
    result = {}

    def sender():
        result["text"] = _talk(tks, "frontend_dev_1", "陈思远", "进度?", wait=True)

    t = threading.Thread(target=sender)
    t.start()
    try:
        assert _wait_until(lambda: role_a.state.value == "WAIT"), "A 未进入 WAIT"
        reply = _talk(tks, "frontend_lead", "顾承宇", "进度 80%")
        assert "已回复给正在等待的" in reply
        t.join(timeout=5)
    finally:
        t.join(timeout=1)
        pool.shutdown(wait=False)
    assert not t.is_alive()
    assert "已收到 陈思远 的回复: 进度 80%" in result["text"]


# ── 跨组拒绝 ───────────────────────────────────────────────

def test_cross_group_rejected(tmp_path, monkeypatch):
    """跨组成员 talk 被拒绝, 提示使用邮件 (send_email)."""
    pool, tks = _setup_roles(
        tmp_path, monkeypatch,
        _role("郭晓东", "tester_1", "测试组"),
        _role("王建国", "architect", "架构与版本组"),
    )
    result = _talk(tks, "tester_1", "王建国", "有个架构问题")
    assert "仅限同组成员" in result
    assert "测试组" in result and "架构与版本组" in result
    assert "send_email" in result          # 引导跨组改用邮件
    assert pool.get_role("architect").queue_depth == 0   # 未送达


def test_cross_group_wait_rejected(tmp_path, monkeypatch):
    """跨组 wait=true 同样被拒绝, 不进入 WAIT 状态."""
    pool, tks = _setup_roles(
        tmp_path, monkeypatch,
        _role("郭晓东", "tester_1", "测试组"),
        _role("方谨言", "release_manager", "架构与版本组"),
    )
    role_a = pool.get_role("tester_1")
    result = _talk(tks, "tester_1", "方谨言", "有急事", wait=True)
    assert "仅限同组成员" in result
    assert role_a.state.value == "ON_DUTY_IDLE"   # 未进入 WAIT


# ── 未分组不受限 ───────────────────────────────────────────

def test_ungrouped_roles_unrestricted(tmp_path, monkeypatch):
    """未分组角色 (招聘新人/旧调用) 不受组限制, 可自由 talk."""
    pool, tks = _setup_roles(
        tmp_path, monkeypatch,
        _role("新人", "newbie_1", ""),
        _role("顾承宇", "frontend_dev_1", "前端开发组"),
    )
    # 未分组 → 分组
    r1 = _talk(tks, "newbie_1", "顾承宇", "你好")
    assert "消息已发送给 顾承宇" in r1
    # 分组 → 未分组
    r2 = _talk(tks, "frontend_dev_1", "新人", "欢迎")
    assert "消息已发送给 新人" in r2


# ── 花名册显示分组 ─────────────────────────────────────────

def test_roster_shows_group(tmp_path, monkeypatch):
    """花名册显示成员所属分组 (方便判断谁可 talk)."""
    monkeypatch.setattr("src.core.roles.JOURNAL_DIR", tmp_path)
    pool = RolePool()
    pool.add_role(_role("顾承宇", "frontend_dev_1", "前端开发组"))
    pool.add_role(_role("林总", "CEO", "领导组"))
    pool.add_role(_role("新人", "newbie_1", ""))
    roster = talk_toolkit.build_team_roster(pool)
    assert "(组: 前端开发组)" in roster
    assert "(组: 领导组)" in roster
    assert "(组: 未分组)" in roster
    assert "frontend_dev_1" not in roster   # role_id 依旧不暴露
