package com.agent.software.tool.mail;

import com.agent.software.agent.AgentDirectory;
import com.agent.software.agent.AgentState;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.MailId;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Payload;
import com.agent.software.tool.mail.Mailbox.OutgoingMail;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileMailbox}（公司虚拟邮箱）+ {@link EmailToolkit} 测试
 * （迁移自 master {@code services/MailServiceTest}）。
 *
 * <p>不复刻 master 的英文文案，只保留行为要求：{@code addressOf} 是地址唯一权威、
 * send 同时落盘 to 与 cc、收件箱未读/已读状态、投递监听、无收件人报错、工具层参数校验。
 * 不做真实 SMTP 外发。
 */
class FileMailboxTest {

    @TempDir
    Path tmp;

    private static final String SUFFIX = "company.com";

    private AppPaths paths;

    @BeforeEach
    void setUp() {
        paths = AppPaths.resolve(new AppConfig.Storage(tmp.resolve("data").toString()));
    }

    private AppConfig.Mail mailConfig() {
        return new AppConfig.Mail(SUFFIX, new AppConfig.Mail.Smtp("", 587, "", "", "", true));
    }

    private FileMailbox mailbox() {
        return new FileMailbox(mailConfig(), paths);
    }

    private static RoleSpec spec(String id, String name, String username, String group) {
        return RoleSpec.builder()
                .id(new RoleId(id)).name(name).username(username).group(group).toolkits(java.util.Set.of())
                .build();
    }

    // ── 地址分配：唯一权威 ─────────────────────────────────────

    @Test
    void 地址由用户名与后缀推导() {
        FileMailbox mail = mailbox();
        RoleSpec guo = spec("tester_1", "Guo Xiaodong", "guoxiaodong", "Testing Group");
        assertEquals("guoxiaodong@example.com", new FileMailbox(
                new AppConfig.Mail("example.com", new AppConfig.Mail.Smtp("", 587, "", "", "", true)),
                paths).addressOf(guo));
        assertEquals("guoxiaodong@company.cn", new FileMailbox(
                new AppConfig.Mail("company.cn", new AppConfig.Mail.Smtp("", 587, "", "", "", true)),
                paths).addressOf(guo));
        // 带 @ 的后缀被归一化
        assertEquals("guoxiaodong@company.com", new FileMailbox(
                new AppConfig.Mail("@company.com", new AppConfig.Mail.Smtp("", 587, "", "", "", true)),
                paths).addressOf(guo));
        assertThrows(DomainError.class, () -> mail.addressOf(null));
    }

    @Test
    void 显式email优先于用户名推导() {
        // master MailService.emailFor：显式 email 字段直接生效
        RoleSpec guo = RoleSpec.builder()
                .id(new RoleId("tester_1")).name("Guo Xiaodong").username("guoxiaodong")
                .email("dx.guo@corp.cn").group("").toolkits(java.util.Set.of())
                .build();
        assertEquals("dx.guo@corp.cn", mailbox().addressOf(guo));
    }

    @Test
    void 用户名缺失时退回roleId() {
        RoleSpec role = RoleSpec.builder()
                .id(new RoleId("hr")).name("HR").username("  ").toolkits(java.util.Set.of())
                .build();
        assertEquals("hr@company.com", mailbox().addressOf(role));
    }

    // ── 发送 / 收件箱 ──────────────────────────────────────────

    @Test
    void 发送落盘到收件箱并标记未读() {
        FileMailbox mail = mailbox();
        RoleSpec a = spec("tester_1", "Guo Xiaodong", "guoxiaodong", "");
        RoleSpec b = spec("architect", "Wang Jianguo", "wangjianguo", "");

        MailId id = mail.send(new OutgoingMail(mail.addressOf(a), a.name(),
                List.of(mail.addressOf(b)), List.of(), "Test report",
                "Found a login page bug; see the attachment for details."));
        assertNotNull(id);

        List<MailMessage> inbox = mail.inbox(mail.addressOf(b), 0);
        assertEquals(1, inbox.size());
        assertEquals("Test report", inbox.get(0).subject());
        assertEquals("Guo Xiaodong", inbox.get(0).fromName());
        assertEquals(1, mail.unreadCount(mail.addressOf(b)));

        Optional<MailMessage> opened = mail.read(mail.addressOf(b), inbox.get(0).id());
        assertTrue(opened.isPresent());
        assertTrue(opened.get().body().contains("login page bug"));
        assertEquals(0, mail.unreadCount(mail.addressOf(b)));
    }

    @Test
    void 收件人与抄送人都落盘且状态互不影响() {
        FileMailbox mail = mailbox();
        RoleSpec ceo = spec("CEO", "Lin Zong", "linzong", "Leadership Group");
        RoleSpec coo = spec("COO", "Chen Zong", "chenzong", "Leadership Group");
        RoleSpec hr = spec("HR", "Wang Renshi", "wangrenshi", "Leadership Group");

        mail.send(new OutgoingMail(mail.addressOf(ceo), ceo.name(),
                List.of(mail.addressOf(coo)), List.of(mail.addressOf(hr)),
                "Weekly meeting agenda", "We will meet tomorrow morning."));

        assertEquals(1, mail.inbox(mail.addressOf(coo), 0).size());
        assertEquals(1, mail.inbox(mail.addressOf(hr), 0).size());

        // 收件人读信不影响抄送人的未读计数
        mail.read(mail.addressOf(coo), mail.inbox(mail.addressOf(coo), 0).get(0).id());
        assertEquals(0, mail.unreadCount(mail.addressOf(coo)));
        assertEquals(1, mail.unreadCount(mail.addressOf(hr)));
    }

    @Test
    void 收件箱最新在前且支持limit() throws InterruptedException {
        FileMailbox mail = mailbox();
        RoleSpec a = spec("a", "A", "a", "");
        RoleSpec b = spec("b", "B", "b", "");

        mail.send(new OutgoingMail(mail.addressOf(a), a.name(), List.of(mail.addressOf(b)),
                List.of(), "第一封", "body-1"));
        Thread.sleep(15);
        mail.send(new OutgoingMail(mail.addressOf(a), a.name(), List.of(mail.addressOf(b)),
                List.of(), "第二封", "body-2"));

        List<MailMessage> all = mail.inbox(mail.addressOf(b), 0);
        assertEquals(2, all.size());
        assertEquals("第二封", all.get(0).subject(), "最新的邮件应排在最前");
        assertEquals("第一封", all.get(1).subject());

        List<MailMessage> limited = mail.inbox(mail.addressOf(b), 1);
        assertEquals(1, limited.size());
        assertEquals("第二封", limited.get(0).subject());
    }

    @Test
    void 无收件人时抛DomainError() {
        FileMailbox mail = mailbox();
        DomainError error = assertThrows(DomainError.class, () -> mail.send(
                new OutgoingMail("a@company.com", "A", List.of(), List.of(), "s", "b")));
        assertEquals("mail.no-recipient", error.code());
        assertThrows(DomainError.class, () -> mail.send(null));
        assertEquals(0, mail.unreadCount("a@company.com"));
    }

    @Test
    void 投递监听器按收件人触发() {
        FileMailbox mail = mailbox();
        RoleSpec a = spec("a", "A", "a", "");
        RoleSpec b = spec("b", "B", "b", "");
        RoleSpec c = spec("c", "C", "c", "");

        List<String> delivered = new ArrayList<>();
        mail.onDelivery((message, recipient) -> delivered.add(recipient + "|" + message.subject()));

        mail.send(new OutgoingMail(mail.addressOf(a), a.name(),
                List.of(mail.addressOf(b)), List.of(mail.addressOf(c)), "主题", "正文"));

        assertEquals(List.of(mail.addressOf(b) + "|主题", mail.addressOf(c) + "|主题"), delivered);
    }

    @Test
    void 收件箱跨实例持久化() {
        FileMailbox mail = mailbox();
        RoleSpec a = spec("a", "A", "a", "");
        RoleSpec b = spec("b", "B", "b", "");
        mail.send(new OutgoingMail(mail.addressOf(a), a.name(), List.of(mail.addressOf(b)),
                List.of(), "Archive", "Can I still see this after a restart?"));

        List<MailMessage> inbox = mailbox().inbox(mail.addressOf(b), 0);
        assertEquals(1, inbox.size());
        assertEquals("Archive", inbox.get(0).subject());
    }

    @Test
    void 读未知邮件返回空() {
        FileMailbox mail = mailbox();
        assertTrue(mail.read("nobody@company.com", MailId.generate()).isEmpty());
        assertTrue(mail.read("nobody@company.com", null).isEmpty());
        assertEquals(0, mail.inbox("  ", 0).size());
        assertEquals(0, mail.unreadCount(""));
    }

    // ── MailMessage.preview ────────────────────────────────────

    @Test
    void 预览格式含未读标记与截断() {
        MailMessage message = new MailMessage(MailId.generate(), "a@company.com", "  郭  晓东 ",
                List.of("b@company.com"), List.of(), " 周报 ", "第一行\n第二行", Instant.now(), false);
        assertEquals("[未读] 郭 晓东 周报：第一行 第二行", message.preview());

        MailMessage read = new MailMessage(message.id(), message.fromEmail(), message.fromName(),
                message.to(), message.cc(), message.subject(), message.body(), message.sentAt(), true);
        assertTrue(read.preview().startsWith("[已读] "), read.preview());

        String longBody = "a".repeat(200);
        MailMessage truncated = new MailMessage(MailId.generate(), "a@company.com", "X",
                List.of("b@company.com"), List.of(), "S", longBody, Instant.now(), false);
        assertTrue(truncated.preview().endsWith("a".repeat(60)), "正文应截断到 60 字");
    }

    // ── SMTP：未配置只报错，不真发 ──────────────────────────────

    @Test
    void 未配置SMTP时发送抛DomainError() {
        SmtpSender sender = new SmtpSender(new AppConfig.Mail.Smtp("", 587, "", "", "", true));
        DomainError error = assertThrows(DomainError.class, () -> sender.send(
                new OutgoingMail("a@company.com", "A", List.of("x@external.com"), List.of(), "s", "b")));
        assertEquals("mail.smtp.unconfigured", error.code());
    }

    @Test
    void 未配置SMTP时对外地址也只落内部邮箱() {
        FileMailbox mail = mailbox();
        RoleSpec a = spec("a", "A", "a", "");
        // 外域收件人在未配置 SMTP 时不应触发任何网络调用，只是内部落盘
        MailId id = mail.send(new OutgoingMail(mail.addressOf(a), a.name(),
                List.of("outsider@external.com"), List.of(), "s", "b"));
        assertNotNull(id);
        assertEquals(1, mail.inbox("outsider@external.com", 0).size());
    }

    // ── EmailToolkit ──────────────────────────────────────────

    private static final class FakeDirectory implements AgentDirectory {

        private final Map<RoleId, RoleSpec> byId = new LinkedHashMap<>();

        void add(RoleSpec... specs) {
            for (RoleSpec spec : specs) {
                byId.put(spec.id(), spec);
            }
        }

        @Override
        public Optional<RoleSpec> spec(RoleId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<RoleSpec> specs() {
            return List.copyOf(byId.values());
        }

        @Override
        public Optional<AgentState> stateOf(RoleId id) {
            return Optional.empty();
        }
    }

    private static Tool tool(Toolkit toolkit, String name) {
        return toolkit.instantiate().stream()
                .filter(t -> name.equals(t.spec().name()))
                .findFirst()
                .orElseThrow();
    }

    private static AgentDirectory directory() {
        FakeDirectory directory = new FakeDirectory();
        directory.add(
                spec("tester_1", "Guo Xiaodong", "guoxiaodong", "Testing Group"),
                spec("architect", "Wang Jianguo", "wangjianguo", "Architecture & Release Group"),
                spec("frontend_dev_1", "Gu Chengyu", "guchengyu", "Frontend Development Group"),
                spec("frontend_lead", "Chen Siyuan", "chensiyuan", "Frontend Development Group"),
                spec("CEO", "Lin Zong", "linzong", "Leadership Group"));
        return directory;
    }

    @Test
    void send_email按姓名解析收件人() {
        FileMailbox mail = mailbox();
        AgentDirectory directory = directory();
        EmailToolkit toolkit = new EmailToolkit(mail, directory);
        assertEquals("email", toolkit.id());
        assertEquals(List.of("send_email", "read_mail", "open_mail", "mail_address_book"),
                toolkit.instantiate().stream().map(t -> t.spec().name()).toList());

        ToolResult result = tool(toolkit, "send_email").invoke(new RoleId("tester_1"),
                Payload.of("to", "Wang Jianguo").with("subject", "Cross-team communication")
                        .with("body", "Contacting the architect via email"));
        assertFalse(result.error(), result.text());

        List<MailMessage> inbox = mail.inbox("wangjianguo@company.com", 0);
        assertEquals(1, inbox.size());
        assertEquals("Guo Xiaodong", inbox.get(0).fromName());
    }

    @Test
    void send_email未知收件人提示通讯录() {
        EmailToolkit toolkit = new EmailToolkit(mailbox(), directory());
        ToolResult result = tool(toolkit, "send_email").invoke(new RoleId("tester_1"),
                Payload.of("to", "Nonexistent colleague").with("subject", "x").with("body", "y"));
        assertTrue(result.error());
        assertTrue(result.text().contains("mail_address_book"), result.text());
    }

    @Test
    void send_email缺少必填参数报错() {
        EmailToolkit toolkit = new EmailToolkit(mailbox(), directory());
        Tool send = tool(toolkit, "send_email");
        assertTrue(send.invoke(new RoleId("tester_1"),
                Payload.of("subject", "x").with("body", "y")).error());
        assertTrue(send.invoke(new RoleId("tester_1"),
                Payload.of("to", "Wang Jianguo").with("body", "y")).error());
        assertTrue(send.invoke(new RoleId("tester_1"),
                Payload.of("to", "Wang Jianguo").with("subject", "x")).error());
    }

    @Test
    void read_mail与open_mail工具() {
        FileMailbox mail = mailbox();
        AgentDirectory directory = directory();
        EmailToolkit toolkit = new EmailToolkit(mail, directory);
        RoleId guo = new RoleId("tester_1");
        RoleId wang = new RoleId("architect");

        tool(toolkit, "send_email").invoke(guo,
                Payload.of("to", "Wang Jianguo").with("subject", "Integration session notes")
                        .with("body", "Integration tomorrow afternoon"));

        ToolResult listed = tool(toolkit, "read_mail").invoke(wang, Payload.of("limit", 5));
        assertFalse(listed.error());
        assertTrue(listed.text().contains("Integration session notes"), listed.text());
        assertTrue(listed.text().contains("未读"), listed.text());

        String messageId = mail.inbox("wangjianguo@company.com", 0).get(0).id().value();
        assertTrue(listed.text().contains(messageId), listed.text());

        ToolResult opened = tool(toolkit, "open_mail").invoke(wang, Payload.of("id", messageId));
        assertFalse(opened.error(), opened.text());
        assertTrue(opened.text().contains("Integration tomorrow afternoon"), opened.text());
        assertEquals(0, mail.unreadCount("wangjianguo@company.com"));

        ToolResult bad = tool(toolkit, "open_mail").invoke(wang, Payload.of("id", "nope"));
        assertTrue(bad.error());
        assertTrue(tool(toolkit, "open_mail").invoke(wang, Payload.empty()).error());
    }

    @Test
    void mail_address_book按组列出且支持过滤() {
        FileMailbox mail = mailbox();
        EmailToolkit toolkit = new EmailToolkit(mail, directory());
        Tool book = tool(toolkit, "mail_address_book");

        ToolResult all = book.invoke(new RoleId("tester_1"), Payload.empty());
        assertFalse(all.error(), all.text());
        assertTrue(all.text().contains("[Frontend Development Group]"), all.text());
        assertTrue(all.text().contains("[Leadership Group]"), all.text());
        assertTrue(all.text().contains("Gu Chengyu <guchengyu@company.com>"), all.text());
        assertTrue(all.text().contains("Chen Siyuan <chensiyuan@company.com>"), all.text());
        assertTrue(all.text().contains("Lin Zong <linzong@company.com>"), all.text());
        assertFalse(all.text().contains("frontend_dev_1"), "通讯录不应暴露 role_id");

        ToolResult filtered = book.invoke(new RoleId("tester_1"),
                Payload.of("keyword", "Frontend Development Group"));
        assertFalse(filtered.text().contains("[Leadership Group]"), filtered.text());
        assertTrue(filtered.text().contains("Gu Chengyu"), filtered.text());

        ToolResult nothing = book.invoke(new RoleId("tester_1"), Payload.of("keyword", "zzz-nobody"));
        assertTrue(nothing.text().contains("没有匹配"), nothing.text());
    }
}
