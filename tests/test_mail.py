"""公司邮件系统测试 (MailService + email 工具类).

覆盖:
  - 邮箱地址分配: username@用户自定义后缀 (MAIL_SUFFIX 可覆盖)
  - 虚拟投递: send_email → 对方 read_mail 可见, open_mail 标记已读
  - 收件人解析: 人名/邮箱/多人/抄送, 未知收件人报错提示
  - 通讯录: 按分组列出成员邮箱 (不暴露 role_id)
  - 持久化: 发送后落盘, 新实例可恢复
  - SMTP 模式: 配置 SMTP_HOST 后真实发送 (mock smtplib), 内部邮箱仍留副本
"""

from __future__ import annotations

from src.core.mail_service import MailConfig, MailService
from src.core.roles import AgentRole, RolePool
from src.python_tools.email_toolkit import create_email_toolkit


def _make_role(name: str, role_id: str, group: str = "") -> AgentRole:
    """构造带分组/拼音用户名的角色 (username 由拼音表推导)."""
    return AgentRole(name=name, role_id=role_id, group=group)


def _mk_service(tmp_path, suffix: str = "company.com", **cfg_kw) -> MailService:
    """构造隔离的 MailService (数据目录指向 tmp_path)."""
    cfg = MailConfig(suffix=suffix, data_dir=str(tmp_path), **cfg_kw)
    return MailService(config=cfg, data_dir=str(tmp_path))


def _mk_toolkit(role: AgentRole, pool: RolePool, service: MailService):
    """构造绑定好的 email 工具类."""
    tk = create_email_toolkit(service)
    tk.bind("role", role)
    return tk


# ── 邮箱地址分配 ───────────────────────────────────────────

def test_email_address_from_username_and_suffix(tmp_path):
    """每位成员都有邮箱: username@后缀; 后缀用户自定义 (MAIL_SUFFIX)."""
    svc = _mk_service(tmp_path, suffix="example.com")
    role = _make_role("郭晓东", "tester_1")
    assert svc.email_for(role) == "guoxiaodong@example.com"

    svc2 = _mk_service(tmp_path, suffix="company.cn")
    assert svc2.email_for(role) == "guoxiaodong@company.cn"


def test_email_explicit_field_wins(tmp_path):
    """角色显式指定 email 字段时优先."""
    svc = _mk_service(tmp_path)
    role = AgentRole(name="郭晓东", role_id="tester_1", email="dx.guo@corp.cn")
    assert svc.email_for(role) == "dx.guo@corp.cn"


def test_agentrole_mail_address_property(tmp_path, monkeypatch):
    """AgentRole.mail_address 走共享服务 (与工具类同一分配规则)."""
    monkeypatch.setenv("MAIL_SUFFIX", "mycompany.com")
    import src.core.mail_service as mail_mod
    monkeypatch.setattr(mail_mod, "_MAIL_SERVICE", None)  # 重建单例读新配置
    role = _make_role("林总", "CEO")
    assert role.mail_address == "linzong@mycompany.com"


# ── 虚拟投递往返 ───────────────────────────────────────────

def test_virtual_send_and_inbox(tmp_path):
    """虚拟模式: 发送 → 对方收件箱可见, 未读计数正确, 打开后标记已读."""
    svc = _mk_service(tmp_path)
    a = _make_role("郭晓东", "tester_1", "测试组")
    b = _make_role("王建国", "architect", "架构与版本组")

    result = svc.send(
        sender_email=svc.email_for(a), sender_name=a.name,
        to=[svc.email_for(b)], subject="测试报告",
        body="发现一个登录页 bug, 详见附件。",
    )
    assert "邮件已发送给" in result
    assert "虚拟邮箱投递" in result

    inbox = svc.inbox(svc.email_for(b))
    assert len(inbox) == 1
    assert inbox[0].subject == "测试报告"
    assert inbox[0].sender_name == "郭晓东"
    assert svc.unread_count(svc.email_for(b)) == 1

    msg = svc.read(svc.email_for(b), inbox[0].message_id)
    assert msg is not None and "登录页 bug" in msg.body
    assert svc.unread_count(svc.email_for(b)) == 0   # 已读


def test_send_to_multiple_and_cc(tmp_path):
    """多人收件 + 抄送: 每人都能在自己邮箱看到 (已读标记独立)."""
    svc = _mk_service(tmp_path)
    a = _make_role("林总", "CEO", "领导组")
    b = _make_role("陈总", "COO", "领导组")
    c = _make_role("王人事", "HR", "领导组")

    svc.send(
        sender_email=svc.email_for(a), sender_name=a.name,
        to=[svc.email_for(b)], cc=[svc.email_for(c)],
        subject="周会安排", body="明天上午开会。",
    )
    assert len(svc.inbox(svc.email_for(b))) == 1
    assert len(svc.inbox(svc.email_for(c))) == 1
    # 收件人已读不影响抄送人
    svc.read(svc.email_for(b), svc.inbox(svc.email_for(b))[0].message_id)
    assert svc.unread_count(svc.email_for(b)) == 0
    assert svc.unread_count(svc.email_for(c)) == 1


# ── 持久化 ─────────────────────────────────────────────────

def test_persistence_roundtrip(tmp_path):
    """发送后落盘 data/mail/mailboxes.json; 新实例加载后仍可读."""
    svc = _mk_service(tmp_path)
    a = _make_role("郭晓东", "tester_1")
    b = _make_role("王建国", "architect")
    svc.send(sender_email=svc.email_for(a), sender_name=a.name,
             to=[svc.email_for(b)], subject="存档", body="重启后还能看到吗")

    svc2 = _mk_service(tmp_path)
    inbox = svc2.inbox(svc.email_for(b))
    assert len(inbox) == 1 and inbox[0].subject == "存档"


# ── SMTP 真实发送 ──────────────────────────────────────────

class _FakeSMTP:
    """记录 sendmail 调用的假 SMTP 客户端."""
    instances: list["_FakeSMTP"] = []
    starttls_called = 0
    login_called = 0

    def __init__(self, host, port, timeout=30):
        self.host = host
        self.port = port
        self.sent: list[tuple[str, list[str], str]] = []
        _FakeSMTP.instances.append(self)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def starttls(self):
        _FakeSMTP.starttls_called += 1

    def login(self, user, password):
        _FakeSMTP.login_called += 1
        self.user, self.password = user, password

    def sendmail(self, from_addr, to_addrs, msg):
        self.sent.append((from_addr, list(to_addrs), msg))


def test_smtp_mode_sends_real_mail(tmp_path, monkeypatch):
    """配置 SMTP_HOST 后自动切换真实发送 (mock smtplib); 内部邮箱仍有副本."""
    _FakeSMTP.instances = []
    _FakeSMTP.starttls_called = 0
    _FakeSMTP.login_called = 0
    monkeypatch.setattr("src.core.mail_service.smtplib.SMTP", _FakeSMTP)
    monkeypatch.setattr("src.core.mail_service.smtplib.SMTP_SSL", _FakeSMTP)

    svc = _mk_service(tmp_path, smtp_host="smtp.example.com", smtp_port=587,
                      smtp_user="robot", smtp_password="secret")
    assert svc.config.mode == "smtp"
    a = _make_role("郭晓东", "tester_1")
    b = _make_role("王建国", "architect")

    result = svc.send(sender_email=svc.email_for(a), sender_name=a.name,
                      to=[svc.email_for(b)], subject="真实邮件",
                      body="这是一封通过 SMTP 发送的邮件")
    assert "SMTP 真实发送" in result

    # smtplib 被调用: 1 个实例, 登录 + 发送
    assert len(_FakeSMTP.instances) == 1
    fake = _FakeSMTP.instances[0]
    assert fake.host == "smtp.example.com"
    assert _FakeSMTP.login_called == 1
    assert len(fake.sent) == 1
    from_addr, to_addrs, msg = fake.sent[0]
    assert from_addr == "robot"
    assert to_addrs == [svc.email_for(b)]
    # 非 ASCII 正文会被 MIME 编码, 解析后校验主题与正文
    from email import policy
    from email.parser import BytesParser
    parsed = BytesParser(policy=policy.default).parsebytes(msg.encode())
    assert parsed["Subject"] == "真实邮件"
    assert "通过 SMTP 发送的邮件" in parsed.get_body().get_content()

    # 内部邮箱仍有副本 (角色可继续在模拟内网阅读)
    inbox = svc.inbox(svc.email_for(b))
    assert len(inbox) == 1
    assert inbox[0].via_smtp is True


def test_smtp_failure_returns_error(tmp_path, monkeypatch):
    """SMTP 发送失败 → 返回错误, 不投递内部邮箱 (便于发现配置问题)."""
    class _BrokenSMTP:
        def __init__(self, *a, **k): pass
        def __enter__(self): return self
        def __exit__(self, *e): return False
        def sendmail(self, *a, **k):
            raise ConnectionError("SMTP server unreachable")

    monkeypatch.setattr("src.core.mail_service.smtplib.SMTP", _BrokenSMTP)
    monkeypatch.setattr("src.core.mail_service.smtplib.SMTP_SSL", _BrokenSMTP)
    svc = _mk_service(tmp_path, smtp_host="smtp.example.com")
    a = _make_role("郭晓东", "tester_1")
    b = _make_role("王建国", "architect")
    result = svc.send(sender_email=svc.email_for(a), sender_name=a.name,
                      to=[svc.email_for(b)], subject="x", body="y")
    assert result.startswith("错误:")
    assert svc.inbox(svc.email_for(b)) == []   # 未投递


# ── email 工具类 (LLM 调用面) ──────────────────────────────

def test_send_email_tool_by_person_name(tmp_path):
    """send_email 的 to 用成员姓名即可送达 (内部解析为邮箱)."""
    pool = RolePool()
    a = _make_role("郭晓东", "tester_1", "测试组")
    b = _make_role("王建国", "architect", "架构与版本组")
    pool.add_role(a); pool.add_role(b)
    a._pool = pool  # 模拟 start() 后回填
    svc = _mk_service(tmp_path)
    tk = _mk_toolkit(a, pool, svc)

    result = tk._tools["send_email"].handler({
        "to": "王建国", "subject": "跨组沟通", "body": "用邮件联系架构师"})
    assert "邮件已发送给" in result
    inbox = svc.inbox(svc.email_for(b))
    assert len(inbox) == 1
    assert inbox[0].sender_name == "郭晓东"


def test_send_email_unknown_recipient(tmp_path):
    """未知收件人 → 错误并提示查通讯录."""
    pool = RolePool()
    a = _make_role("郭晓东", "tester_1", "测试组")
    pool.add_role(a)
    a._pool = pool
    svc = _mk_service(tmp_path)
    tk = _mk_toolkit(a, pool, svc)

    result = tk._tools["send_email"].handler({
        "to": "不存在的同事", "subject": "x", "body": "y"})
    assert "错误" in result
    assert "mail_address_book" in result


def test_read_and_open_mail_tools(tmp_path):
    """read_mail 列表 + open_mail 全文 (标记已读)."""
    pool = RolePool()
    a = _make_role("郭晓东", "tester_1", "测试组")
    b = _make_role("王建国", "architect", "架构与版本组")
    pool.add_role(a); pool.add_role(b)
    a._pool = pool
    svc = _mk_service(tmp_path)
    tk_a = _mk_toolkit(a, pool, svc)
    tk_b = _mk_toolkit(b, pool, svc)

    svc.send(sender_email=svc.email_for(a), sender_name=a.name,
             to=[svc.email_for(b)], subject="联调说明", body="明天下午联调")

    listed = tk_b._tools["read_mail"].handler({"limit": 5})
    assert "联调说明" in listed
    assert "未读" in listed
    assert "id=" in listed

    opened = tk_b._tools["open_mail"].handler({"message_id": svc.inbox(svc.email_for(b))[0].message_id})
    assert "明天下午联调" in opened
    assert svc.unread_count(svc.email_for(b)) == 0

    bad = tk_b._tools["open_mail"].handler({"message_id": "nope"})
    assert "错误" in bad


def test_address_book_groups_and_emails(tmp_path):
    """通讯录按分组列出成员邮箱; 不暴露 role_id."""
    pool = RolePool()
    a = _make_role("顾承宇", "frontend_dev_1", "前端开发组")
    b = _make_role("陈思远", "frontend_lead", "前端开发组")
    c = _make_role("林总", "CEO", "领导组")
    for r in (a, b, c):
        pool.add_role(r)
    a._pool = pool
    svc = _mk_service(tmp_path)
    tk = _mk_toolkit(a, pool, svc)

    book = tk._tools["mail_address_book"].handler({})
    assert "【前端开发组】" in book
    assert "【领导组】" in book
    assert "顾承宇 <guchengyu@company.com>" in book
    assert "陈思远 <chensiyuan@company.com>" in book
    assert "林总 <linzong@company.com>" in book
    assert "frontend_dev_1" not in book      # role_id 不暴露

    filtered = tk._tools["mail_address_book"].handler({"group": "前端开发组"})
    assert "【领导组】" not in filtered
    notfound = tk._tools["mail_address_book"].handler({"group": "不存在组"})
    assert "错误" in notfound
