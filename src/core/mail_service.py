"""公司邮件系统核心 (MailService) — 员工邮件收发.

职责:
  - 为每位员工分配邮箱地址: username@<用户自定义后缀> (MAIL_SUFFIX 环境变量),
    显式指定 email 字段时优先使用.
  - 虚拟邮箱 (默认): 邮件投递到内存邮箱, 按需持久化到 data/mail/mailboxes.json
    (json 原子写, 重启后可恢复).
  - 真实邮箱: 配置 SMTP 环境变量后自动切换为 smtplib 真实发送;
    同时仍把邮件副本投递到内部员工邮箱 (模拟内网阅读, 方便角色处理).

环境变量 (用户自定义):
    MAIL_SUFFIX    邮箱域名后缀 (默认 "company.com"), 如 @company.com
    SMTP_HOST      SMTP 服务器地址 (设置后启用真实发送, 否则虚拟实现)
    SMTP_PORT      SMTP 端口 (默认 587)
    SMTP_USER      SMTP 登录用户名 (可选)
    SMTP_PASSWORD  SMTP 登录密码 (可选)
    SMTP_FROM      发件人地址 (默认取 SMTP_USER, 再回退到发送者本人地址)
    SMTP_USE_SSL   是否 SSL 连接 (true/false; 未设置时按端口判断: 465 → SSL)
    MAIL_DATA_DIR  邮箱数据目录 (默认 data/mail)

接口文档 (模块结构与方法):

类与方法:
    MailMessage: 一封邮件 (消息 id/发件人/收件人/主题/正文/时间/已读标记).
    MailConfig:  邮件配置 (从环境变量读取; 也可编程构造).
    MailService:
        - email_for(): 角色 → 邮箱地址 (后缀用户自定义).
        - send(): 发送邮件 (虚拟投递 / SMTP 真实发送 + 内部副本).
        - inbox(): 读取某个邮箱的收件列表 (新到优先).
        - unread_count(): 未读邮件数.
        - read(): 打开一封邮件 (标记已读).
        - describe(): 当前投递方式描述 (虚拟 / SMTP).
        - load()/save(): 邮箱持久化 (data/mail/mailboxes.json).
"""
from __future__ import annotations

import json
import logging
import os
import smtplib
import threading
import time
import uuid
from dataclasses import dataclass, field
from email.mime.text import MIMEText
from email.utils import formataddr, formatdate
from pathlib import Path
from typing import Any, Optional

logger = logging.getLogger(__name__)

DEFAULT_MAIL_SUFFIX = "company.com"   # 邮箱域名后缀 (用户可用 MAIL_SUFFIX 覆盖)
DEFAULT_MAIL_DIR = "data/mail"        # 邮箱数据目录
MAILBOX_FILE = "mailboxes.json"       # 持久化文件名


# ── MailConfig ─────────────────────────────────────────────

@dataclass
class MailConfig:
    """邮件配置: 后缀 + SMTP 参数 (从环境变量读取, 也可编程构造).

    参数:
        suffix:        邮箱域名后缀 (用户自定义, 默认 company.com).
        smtp_host:     SMTP 服务器地址; 空 = 虚拟实现.
        smtp_port:     SMTP 端口.
        smtp_user:     SMTP 登录用户名.
        smtp_password: SMTP 登录密码.
        smtp_from:     发件人地址 (回退: SMTP_USER → 发送者本人).
        use_ssl:       SSL 连接 (None = 按端口判断: 465 → SSL).
        data_dir:      邮箱数据目录 (默认 data/mail).
    """
    suffix: str = DEFAULT_MAIL_SUFFIX
    smtp_host: str = ""
    smtp_port: int = 587
    smtp_user: str = ""
    smtp_password: str = ""
    smtp_from: str = ""
    use_ssl: Optional[bool] = None
    data_dir: str = DEFAULT_MAIL_DIR

    @classmethod
    def from_env(cls) -> "MailConfig":
        """从环境变量读取配置 (后缀与 SMTP 均可由用户定义).

        返回:
            MailConfig 实例.
        """
        use_ssl: Optional[bool] = None
        ssl_raw = os.environ.get("SMTP_USE_SSL", "").strip().lower()
        if ssl_raw in ("1", "true", "yes", "on"):
            use_ssl = True
        elif ssl_raw in ("0", "false", "no", "off"):
            use_ssl = False
        try:
            port = int(os.environ.get("SMTP_PORT", "587") or "587")
        except ValueError:
            port = 587
        return cls(
            suffix=(os.environ.get("MAIL_SUFFIX", "").strip()
                    or DEFAULT_MAIL_SUFFIX),
            smtp_host=os.environ.get("SMTP_HOST", "").strip(),
            smtp_port=port,
            smtp_user=os.environ.get("SMTP_USER", "").strip(),
            smtp_password=os.environ.get("SMTP_PASSWORD", ""),
            smtp_from=os.environ.get("SMTP_FROM", "").strip(),
            use_ssl=use_ssl,
            data_dir=os.environ.get("MAIL_DATA_DIR", "").strip()
            or DEFAULT_MAIL_DIR,
        )

    @property
    def mode(self) -> str:
        """投递方式: 'smtp' = 真实发送 (已配置 SMTP), 'virtual' = 虚拟实现."""
        return "smtp" if self.smtp_host else "virtual"


# ── MailMessage ────────────────────────────────────────────

@dataclass
class MailMessage:
    """一封邮件.

    参数:
        message_id:   邮件唯一 id (open_mail 引用).
        sender_email: 发件人邮箱.
        sender_name:  发件人姓名.
        subject:      主题.
        body:         正文.
        recipients:   收件人邮箱列表.
        cc:           抄送邮箱列表.
        timestamp:    发送时间 (unix 秒).
        read:         是否已读 (按收件人邮箱独立标记).
        via_smtp:     是否已通过 SMTP 真实发送.
    """
    message_id: str
    sender_email: str
    sender_name: str
    subject: str
    body: str
    recipients: list[str] = field(default_factory=list)
    cc: list[str] = field(default_factory=list)
    timestamp: float = field(default_factory=time.time)
    read: bool = False
    via_smtp: bool = False

    def to_dict(self) -> dict[str, Any]:
        """MailMessage → 可序列化 dict (持久化用)."""
        return {
            "message_id": self.message_id,
            "sender_email": self.sender_email,
            "sender_name": self.sender_name,
            "subject": self.subject,
            "body": self.body,
            "recipients": list(self.recipients),
            "cc": list(self.cc),
            "timestamp": self.timestamp,
            "read": self.read,
            "via_smtp": self.via_smtp,
        }

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "MailMessage":
        """dict → MailMessage (持久化恢复用)."""
        return cls(
            message_id=d.get("message_id", ""),
            sender_email=d.get("sender_email", ""),
            sender_name=d.get("sender_name", ""),
            subject=d.get("subject", ""),
            body=d.get("body", ""),
            recipients=list(d.get("recipients", [])),
            cc=list(d.get("cc", [])),
            timestamp=float(d.get("timestamp", time.time())),
            read=bool(d.get("read", False)),
            via_smtp=bool(d.get("via_smtp", False)),
        )

    def preview(self, length: int = 60) -> str:
        """单行摘要 (read_mail 列表用)."""
        flag = "已读" if self.read else "未读"
        stamp = time.strftime("%m-%d %H:%M", time.localtime(self.timestamp))
        return (f"[{flag}] {self.sender_name} <{self.sender_email}> "
                f"「{self.subject}」 {self.body[:length]!r} "
                f"({stamp}, id={self.message_id})")

    def full_text(self) -> str:
        """完整邮件内容 (open_mail 用)."""
        stamp = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(self.timestamp))
        to_line = ", ".join(self.recipients)
        cc_line = f"抄送: {', '.join(self.cc)}" if self.cc else ""
        smtp_note = " [已通过 SMTP 真实发送]" if self.via_smtp else ""
        lines = [
            f"发件人: {self.sender_name} <{self.sender_email}>{smtp_note}",
            f"收件人: {to_line}",
        ]
        if cc_line:
            lines.append(cc_line)
        lines += [
            f"时间: {stamp}",
            f"主题: {self.subject}",
            "─" * 40,
            self.body,
        ]
        return "\n".join(lines)


# ── MailService ────────────────────────────────────────────

class MailService:
    """公司邮件服务: 邮箱地址分配 + 收发 (虚拟/SMTP) + 持久化.

    参数:
        config:   MailConfig 实例 (默认从环境变量读取).
        data_dir: 邮箱数据目录覆盖 (默认取 config.data_dir).
    """

    def __init__(self, config: Optional[MailConfig] = None,
                 data_dir: Optional[str] = None):
        self.config = config or MailConfig.from_env()
        self._data_dir = data_dir or self.config.data_dir
        self._mailboxes: dict[str, list[MailMessage]] = {}
        self._lock = threading.RLock()
        self._loaded = False

    # ── 邮箱地址分配 ──────────────────────────────────────

    def email_for(self, role: Any) -> str:
        """角色 → 公司邮箱地址 (后缀由用户定义, 默认 company.com).

        优先级: 角色显式 email 字段 > username@后缀 (username 为空回退
        role_id@后缀). 全部小写, 保证同一员工地址唯一稳定.

        参数:
            role: AgentRole 实例.

        返回:
            该员工的邮箱地址, 如 guoxiaodong@company.com.
        """
        explicit = (getattr(role, "email", "") or "").strip()
        if explicit:
            return explicit
        username = (getattr(role, "username", "") or "").strip()
        local = username or (getattr(role, "role_id", "") or "agent")
        return f"{local}@{self.config.suffix}".lower()

    # ── 发送 ──────────────────────────────────────────────

    def send(self, sender_email: str, sender_name: str,
             to: list[str], subject: str, body: str,
             cc: Optional[list[str]] = None) -> str:
        """发送一封邮件.

        虚拟模式: 投递到每个收件人邮箱 (含抄送), 立即返回.
        SMTP 模式: 先 smtplib 真实发送, 成功后把副本投递到内部收件人邮箱
        (标记 via_smtp, 方便角色在模拟内网继续阅读); 真实发送失败则返回
        错误 (不投递, 便于发现配置问题).

        参数:
            sender_email: 发件人邮箱.
            sender_name:  发件人姓名.
            to:           收件人邮箱列表.
            subject:      主题.
            body:         正文.
            cc:           抄送邮箱列表 (可选).

        返回:
            发送结果摘要 (含投递方式).
        """
        to = [t for t in (to or []) if t]
        cc = [t for t in (cc or []) if t]
        if not to:
            return "错误: 收件人列表为空, 邮件未发送."
        if not subject.strip() and not body.strip():
            return "错误: 主题与正文不能同时为空, 邮件未发送."

        msg = MailMessage(
            message_id=uuid.uuid4().hex[:12],
            sender_email=sender_email,
            sender_name=sender_name,
            subject=subject,
            body=body,
            recipients=list(to),
            cc=cc,
        )

        if self.config.mode == "smtp":
            try:
                self._send_via_smtp(msg)
            except Exception as exc:
                logger.error("SMTP 发送失败: %s", exc)
                return f"错误: SMTP 发送失败: {exc} (邮件未投递)"
            msg.via_smtp = True

        # 虚拟投递 (SMTP 模式也投递内部副本, 供角色在模拟内网阅读)
        with self._lock:
            self._load()
            for addr in to + cc:
                self._mailboxes.setdefault(addr.lower(), []).append(
                    MailMessage.from_dict(msg.to_dict()))  # 每邮箱独立副本 (已读标记独立)
            self._save()

        recipients_desc = ", ".join(f"{addr}" for addr in to)
        way = "已通过 SMTP 真实发送" if msg.via_smtp else "虚拟邮箱投递"
        return (f"邮件已发送给 {recipients_desc}, 主题「{msg.subject}」, "
                f"{way}.")

    def _send_via_smtp(self, msg: MailMessage) -> None:
        """用 smtplib 真实发送邮件 (需已配置 SMTP_HOST).

        参数:
            msg: MailMessage 实例.

        异常:
            发送失败抛异常, 由 send() 捕获转为错误返回.
        """
        cfg = self.config
        mime = MIMEText(msg.body, "plain", "utf-8")
        mime["Subject"] = msg.subject
        mime["From"] = formataddr((msg.sender_name, msg.sender_email))
        mime["To"] = ", ".join(msg.recipients)
        mime["Date"] = formatdate(msg.timestamp, localtime=True)
        if msg.cc:
            mime["Cc"] = ", ".join(msg.cc)

        from_addr = cfg.smtp_from or cfg.smtp_user or msg.sender_email
        use_ssl = cfg.use_ssl if cfg.use_ssl is not None else (cfg.smtp_port == 465)

        if use_ssl:
            server = smtplib.SMTP_SSL(cfg.smtp_host, cfg.smtp_port, timeout=30)
        else:
            server = smtplib.SMTP(cfg.smtp_host, cfg.smtp_port, timeout=30)
            if cfg.smtp_port == 587 or cfg.use_ssl is False:
                server.starttls()
        with server:
            if cfg.smtp_user:
                server.login(cfg.smtp_user, cfg.smtp_password)
            server.sendmail(from_addr, msg.recipients + msg.cc,
                            mime.as_string())

    # ── 收件 ──────────────────────────────────────────────

    def inbox(self, email: str, limit: Optional[int] = None) -> list[MailMessage]:
        """读取某个邮箱的收件列表 (新到优先).

        参数:
            email: 邮箱地址 (不区分大小写).
            limit: 最多返回条数 (None = 全部).

        返回:
            邮件列表, 新到在前.
        """
        with self._lock:
            self._load()
            msgs = list(self._mailboxes.get(email.lower(), []))
        msgs.sort(key=lambda m: m.timestamp, reverse=True)
        if limit is not None:
            msgs = msgs[:limit]
        return msgs

    def unread_count(self, email: str) -> int:
        """未读邮件数.

        参数:
            email: 邮箱地址.

        返回:
            未读数量.
        """
        with self._lock:
            self._load()
            return sum(1 for m in self._mailboxes.get(email.lower(), [])
                       if not m.read)

    def read(self, email: str, message_id: str) -> Optional[MailMessage]:
        """打开一封邮件 (标记为已读).

        参数:
            email:      收件人邮箱.
            message_id: 邮件 id (read_mail 列表返回).

        返回:
            邮件内容, 不存在返回 None.
        """
        with self._lock:
            self._load()
            box = self._mailboxes.get(email.lower())
            if box is None:
                return None
            for m in box:
                if m.message_id == message_id:
                    if not m.read:
                        m.read = True
                        self._save()
                    return m
        return None

    def describe(self) -> str:
        """当前投递方式描述 (给 LLM / 日志看)."""
        if self.config.mode == "smtp":
            return (f"真实邮件 (SMTP: {self.config.smtp_host}:"
                    f"{self.config.smtp_port})")
        return "虚拟邮箱 (未配置 SMTP, 邮件仅内部投递)"

    # ── 持久化 (data/mail/mailboxes.json) ─────────────────

    def _path(self) -> Path:
        return Path(self._data_dir) / MAILBOX_FILE

    def _load(self) -> None:
        """从磁盘加载邮箱 (幂等; 仅在首次访问时读取)."""
        if self._loaded:
            return
        self._loaded = True
        path = self._path()
        if not path.exists():
            return
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            for email, msgs in data.get("mailboxes", {}).items():
                self._mailboxes[email] = [
                    MailMessage.from_dict(m) for m in msgs]
        except Exception as exc:
            logger.warning("邮箱持久化读取失败, 从空邮箱开始: %s", exc)

    def _save(self) -> None:
        """邮箱原子写盘 (tmp + rename, 与 StateStore 同一策略)."""
        try:
            path = self._path()
            path.parent.mkdir(parents=True, exist_ok=True)
            data = {
                "mailboxes": {
                    email: [m.to_dict() for m in msgs]
                    for email, msgs in self._mailboxes.items()
                }
            }
            tmp = path.with_suffix(".json.tmp")
            tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2),
                           encoding="utf-8")
            tmp.replace(path)
        except Exception as exc:
            logger.warning("邮箱持久化写盘失败: %s", exc)

    def load(self) -> None:
        """公开加载入口 (重启后手动恢复)."""
        with self._lock:
            self._load()

    def save(self) -> None:
        """公开保存入口."""
        with self._lock:
            self._save()


# ── 全局共享实例 (与 _MCP_MANAGER 同款懒加载单例) ────────────

_MAIL_SERVICE: Optional[MailService] = None
_MAIL_SERVICE_LOCK = threading.Lock()


def get_mail_service() -> MailService:
    """获取进程级共享 MailService (懒加载, 配置读环境变量)."""
    global _MAIL_SERVICE
    if _MAIL_SERVICE is None:
        with _MAIL_SERVICE_LOCK:
            if _MAIL_SERVICE is None:
                _MAIL_SERVICE = MailService()
    return _MAIL_SERVICE
