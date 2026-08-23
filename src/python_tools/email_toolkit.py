"""公司邮件工具类 (Email ToolKit).

包含:
  - send_email:         给同事发邮件 (跨组沟通首选; 可多人/抄送)
  - read_mail:          查看收件箱 (新到优先, 未读标记)
  - open_mail:          打开一封邮件 (标记已读)
  - mail_address_book:  公司通讯录 (分组显示成员邮箱)

用法:
    from src.python_tools.email_toolkit import create_email_toolkit
    role.add_toolkit(create_email_toolkit())   # 默认装配 (DEFAULT_TOOLKITS)

邮件地址规则: 每位员工一个公司邮箱 username@<后缀>, 后缀由用户通过
环境变量 MAIL_SUFFIX 定义 (默认 company.com); 角色显式 email 字段优先.

投递方式:
  - 虚拟实现 (默认): 邮件投递到内部邮箱 (data/mail/mailboxes.json 持久化)
  - 真实发送: 配置 SMTP_HOST 等环境变量后自动切换 smtplib 真实发送,
    同时保留内部邮箱副本供角色阅读

接口文档 (模块结构与方法):

模块级函数:
    - create_email_toolkit(): 创建邮件工具类 (send_email/read_mail/open_mail/mail_address_book).
    - bind_email_to_toolkit(): 将当前角色绑定到邮件工具类 (由 AgentRole.add_toolkit 内部调用).
"""
from __future__ import annotations

import logging
from typing import Any, Optional

from src.core.mail_service import MailService, get_mail_service
from src.core.tools import ToolKit

logger = logging.getLogger(__name__)


def create_email_toolkit(service: Optional[MailService] = None) -> ToolKit:
    """创建邮件工具类.

    参数:
        service: MailService 实例 (可选; 默认进程级共享实例).

    返回:
        包含 send_email / read_mail / open_mail / mail_address_book 的 ToolKit.
    """
    svc = service or get_mail_service()
    tk = ToolKit(name="email", description="公司邮件工具类 (员工邮件收发)")
    tk.bind("mail", svc)  # 工厂即绑定服务; 角色由 binder 绑定

    def _role() -> Any:
        return tk.require("role", "当前角色")

    def _service() -> MailService:
        return tk.get("mail") or get_mail_service()

    def _resolve_address(pool: Any, value: str) -> str:
        """把一个人名/邮箱解析为邮箱地址.

        参数:
            pool:  RolePool (按人名查角色).
            value: 人名 (如 '郭晓东') 或完整邮箱 (含 @).

        返回:
            邮箱地址; 无法解析返回空串.
        """
        value = (value or "").strip()
        if not value:
            return ""
        if "@" in value:
            return value
        if pool is None:
            return ""
        role = pool.get_role_by_name(value)
        if role is None:
            return ""
        return _service().email_for(role)

    def _resolve_recipients(pool: Any, values: Any) -> tuple[list[str], list[str]]:
        """批量解析收件人 (人名或邮箱混合, 支持列表/逗号分隔字符串).

        参数:
            pool:   RolePool.
            values: 收件人列表 (list[str] 或逗号分隔字符串).

        返回:
            (已解析邮箱列表, 解析失败的人名列表).
        """
        if values is None:
            return [], []
        if isinstance(values, str):
            parts = [v.strip() for v in values.split(",") if v.strip()]
        elif isinstance(values, (list, tuple)):
            parts = [str(v).strip() for v in values if str(v).strip()]
        else:
            parts = [str(values).strip()]
        emails: list[str] = []
        failed: list[str] = []
        for p in parts:
            addr = _resolve_address(pool, p)
            if addr:
                emails.append(addr)
            else:
                failed.append(p)
        return emails, failed

    def _send_email_handler(args: dict[str, Any]) -> str:
        """send_email 工具处理函数.

        参数:
            args: {"to": 收件人 (人名或邮箱, 支持多人), "subject": 主题,
                   "body": 正文, "cc": 抄送 (可选)}

        返回:
            发送结果摘要 (含投递方式).
        """
        to_raw = args.get("to")
        subject = (args.get("subject") or "").strip()
        body = args.get("body") or ""
        cc_raw = args.get("cc")

        if not to_raw:
            return "错误: 'to' (收件人) 为必填参数."
        if not subject and not body.strip():
            return "错误: 'subject' 与 'body' 至少填一个."

        role = _role()
        pool = role.pool
        to, failed = _resolve_recipients(pool, to_raw)
        cc, cc_failed = _resolve_recipients(pool, cc_raw)
        failed += cc_failed

        if not to:
            return ("错误: 收件人无法解析: " + ", ".join(failed or ["(空)"])
                    + "。请先调用 mail_address_book 查看成员姓名/邮箱。")

        sender_email = _service().email_for(role)
        result = _service().send(
            sender_email=sender_email,
            sender_name=role.name,
            to=to,
            subject=subject,
            body=body,
            cc=cc,
        )
        if failed:
            result += f" 注意: 以下收件人未找到, 未发送: {', '.join(failed)}"
        role.journal(f"发送邮件: 「{subject}」 → {', '.join(to)}")
        return result

    def _read_mail_handler(args: dict[str, Any]) -> str:
        """read_mail 工具处理函数: 查看收件箱 (新到优先).

        参数:
            args: {"limit": 最多条数 (默认 10), "unread_only": 只看未读 (默认 false)}

        返回:
            邮件列表 (含 message_id, 供 open_mail 打开).
        """
        role = _role()
        email = _service().email_for(role)
        try:
            limit = int(args.get("limit", 10) or 10)
        except (TypeError, ValueError):
            limit = 10
        unread_only = bool(args.get("unread_only", False))

        msgs = _service().inbox(email, limit=None)
        if unread_only:
            msgs = [m for m in msgs if not m.read]
        msgs = msgs[:max(0, limit)]

        if not msgs:
            return f"收件箱为空 (邮箱 {email})."
        lines = [f"收件箱 {email} ({_service().unread_count(email)} 封未读):"]
        lines += [f"  {i + 1}. {m.preview()}" for i, m in enumerate(msgs)]
        return "\n".join(lines)

    def _open_mail_handler(args: dict[str, Any]) -> str:
        """open_mail 工具处理函数: 打开一封邮件 (标记已读).

        参数:
            args: {"message_id": read_mail 返回的邮件 id}

        返回:
            邮件完整内容.
        """
        message_id = (args.get("message_id") or "").strip()
        if not message_id:
            return "错误: 'message_id' 为必填参数 (read_mail 列表中的 id)."
        role = _role()
        msg = _service().read(_service().email_for(role), message_id)
        if msg is None:
            return (f"错误: 找不到邮件 {message_id}。"
                    f"请先调用 read_mail 查看当前收件箱中的邮件。")
        return msg.full_text()

    def _address_book_handler(args: dict[str, Any]) -> str:
        """mail_address_book 工具处理函数: 公司通讯录 (按组分, 含邮箱).

        参数:
            args: {"group": 只看某组 (可选, 如 '前端开发组')}

        返回:
            通讯录文本 (组名 → 成员姓名 <邮箱> — 职位).
        """
        role = _role()
        pool = role.pool
        group_filter = (args.get("group") or "").strip()
        svc = _service()

        if pool is None:
            return "错误: 当前角色未绑定角色池, 无法获取通讯录."

        members = pool.all_roles()
        by_group: dict[str, list[Any]] = {}
        for r in members:
            g = (getattr(r, "group", "") or "").strip() or "未分组"
            by_group.setdefault(g, []).append(r)

        lines = [f"公司通讯录 (邮箱后缀 @{svc.config.suffix}, "
                 f"共 {len(members)} 人):"]
        for g in sorted(by_group):
            if group_filter and g != group_filter:
                continue
            lines.append(f"【{g}】")
            for r in sorted(by_group[g], key=lambda x: x.name):
                # 只暴露 姓名/邮箱/职位 (职位为空时给职责摘要), 不暴露内部 role_id
                desc = r.title or r.responsibilities or "团队成员"
                lines.append(f"  - {r.name} <{svc.email_for(r)}> — {desc}")
        if group_filter and group_filter not in by_group:
            return (f"错误: 找不到分组「{group_filter}」。"
                    f"可用分组: {', '.join(sorted(by_group))}")
        return "\n".join(lines)

    tk.add_python_tool(
        name="send_email",
        description=(
            "给同事发送公司邮件 (员工之间正式沟通/跨组沟通的方式). "
            "to 用同事姓名 (见 mail_address_book 通讯录) 或完整邮箱地址; "
            "可一次发给多人 (逗号分隔或数组), 需要抄送时用 cc. "
            "发送成功后邮件进入对方收件箱 (read_mail), 对方可回复. "
            "跨组同事沟通请用邮件而不是 talk (talk 仅限同组成员)."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "to": {
                    "type": "string",
                    "description": "收件人: 同事姓名或邮箱, 多人用逗号分隔",
                },
                "subject": {
                    "type": "string",
                    "description": "邮件主题 (一句话概括)",
                },
                "body": {
                    "type": "string",
                    "description": "邮件正文 (具体内容, 越详细对方越好处理)",
                },
                "cc": {
                    "type": "string",
                    "description": "抄送 (可选): 姓名或邮箱, 多人用逗号分隔",
                },
            },
            "required": ["to", "subject", "body"],
        },
        handler=_send_email_handler,
    )

    tk.add_python_tool(
        name="read_mail",
        description=(
            "查看自己的公司邮箱收件箱 (新到优先). "
            "返回邮件列表: 发件人/主题/摘要/已读未读/message_id. "
            "用 open_mail 打开某封邮件的完整内容. "
            "每天开始或空闲时可以查看是否有新邮件."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "最多显示条数 (默认 10)",
                },
                "unread_only": {
                    "type": "boolean",
                    "description": "是否只看未读邮件 (默认 false)",
                },
            },
        },
        handler=_read_mail_handler,
    )

    tk.add_python_tool(
        name="open_mail",
        description=(
            "打开一封邮件查看完整内容 (自动标记为已读). "
            "message_id 来自 read_mail 列表."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "message_id": {
                    "type": "string",
                    "description": "邮件 id (read_mail 返回)",
                },
            },
            "required": ["message_id"],
        },
        handler=_open_mail_handler,
    )

    tk.add_python_tool(
        name="mail_address_book",
        description=(
            "查看公司通讯录: 所有成员按分组列出 (组名 → 姓名 <邮箱> — 职位). "
            "发邮件前若不确定收件人姓名或邮箱, 先调用本工具. "
            "也可用 group 参数只看某个分组."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "group": {
                    "type": "string",
                    "description": "只看某分组 (可选, 如 '前端开发组')",
                },
            },
        },
        handler=_address_book_handler,
    )

    return tk


def bind_email_to_toolkit(toolkit: ToolKit, role: Any) -> None:
    """将当前角色绑定到邮件工具类 (由 AgentRole.add_toolkit 内部调用).

    参数:
        toolkit: email 工具类实例.
        role:    绑定的 AgentRole (发送方身份: 姓名/邮箱/分组).
    """
    toolkit.bind("role", role)
