package com.agent.software.core;

import com.agent.software.role.AgentRole;
import com.agent.software.role.RolePool;
import com.agent.software.role.ToolRegistry;
import com.agent.software.tools.ToolkitBridge;
import com.agent.software.tools.toolkits.email.Email;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公司邮件系统测试 (Python 版 test_mail.py 的 Java 对应物, 虚拟模式).
 * SMTP 真实发送路径在 Java 中依赖 jakarta.mail, 不在单元测试中 mock.
 */
class MailServiceTest {

    @TempDir
    Path tmp;

    /**
     * 测试角色构造: 与模板同名同 id 时, username 直接取自模板 JSON
     * (原 PinyinMap 的拼音派生已并入 role_templates.json 的 username 字段).
     */
    private static AgentRole role(String name, String roleId, String group) {
        AgentRole.Builder b = AgentRole.builder().name(name).roleId(roleId).group(group);
        if (RoleTemplates.TEMPLATES.containsKey(roleId)) {
            AgentRole t = RoleTemplates.getTemplate(roleId);
            if (t.name.equals(name)) {
                b.username(t.username);
            }
        }
        return b.build();
    }

    private MailService service(String suffix) {
        MailService.MailConfig cfg = new MailService.MailConfig();
        cfg.suffix = suffix;
        cfg.dataDir = tmp.toString();
        return new MailService(cfg, tmp.toString());
    }

    // ── 邮箱地址分配 ─────────────────────────────────────

    @Test
    void testEmailAddressFromUsernameAndSuffix() {
        AgentRole r = role("郭晓东", "tester_1", "");
        assertEquals("guoxiaodong@example.com", service("example.com").emailFor(r));
        assertEquals("guoxiaodong@company.cn", service("company.cn").emailFor(r));
    }

    @Test
    void testEmailExplicitFieldWins() {
        AgentRole r = role("郭晓东", "tester_1", "");
        r.email = "dx.guo@corp.cn";
        assertEquals("dx.guo@corp.cn", service("company.com").emailFor(r));
    }

    // ── 虚拟投递往返 ─────────────────────────────────────

    @Test
    void testVirtualSendAndInbox() {
        MailService svc = service("company.com");
        AgentRole a = role("郭晓东", "tester_1", "测试组");
        AgentRole b = role("王建国", "architect", "架构与版本组");
        String result = svc.send(svc.emailFor(a), a.name, List.of(svc.emailFor(b)),
                "测试报告", "发现一个登录页 bug, 详见附件。", null);
        assertTrue(result.contains("邮件已发送给"));
        assertTrue(result.contains("虚拟邮箱投递"));

        List<MailService.MailMessage> inbox = svc.inbox(svc.emailFor(b), null);
        assertEquals(1, inbox.size());
        assertEquals("测试报告", inbox.get(0).subject);
        assertEquals("郭晓东", inbox.get(0).senderName);
        assertEquals(1, svc.unreadCount(svc.emailFor(b)));

        MailService.MailMessage msg = svc.read(svc.emailFor(b), inbox.get(0).messageId);
        assertTrue(msg != null && msg.body.contains("登录页 bug"));
        assertEquals(0, svc.unreadCount(svc.emailFor(b)));
    }

    @Test
    void testSendToMultipleAndCc() {
        MailService svc = service("company.com");
        AgentRole a = role("林总", "CEO", "领导组");
        AgentRole b = role("陈总", "COO", "领导组");
        AgentRole c = role("王人事", "HR", "领导组");
        svc.send(svc.emailFor(a), a.name, List.of(svc.emailFor(b)),
                "周会安排", "明天上午开会。", List.of(svc.emailFor(c)));
        assertEquals(1, svc.inbox(svc.emailFor(b), null).size());
        assertEquals(1, svc.inbox(svc.emailFor(c), null).size());
        // 收件人已读不影响抄送人
        svc.read(svc.emailFor(b), svc.inbox(svc.emailFor(b), null).get(0).messageId);
        assertEquals(0, svc.unreadCount(svc.emailFor(b)));
        assertEquals(1, svc.unreadCount(svc.emailFor(c)));
    }

    @Test
    void testPersistenceRoundtrip() {
        MailService svc = service("company.com");
        AgentRole a = role("郭晓东", "tester_1", "");
        AgentRole b = role("王建国", "architect", "");
        svc.send(svc.emailFor(a), a.name, List.of(svc.emailFor(b)), "存档", "重启后还能看到吗", null);
        MailService svc2 = service("company.com");
        List<MailService.MailMessage> inbox = svc2.inbox(svc.emailFor(b), null);
        assertEquals(1, inbox.size());
        assertEquals("存档", inbox.get(0).subject);
    }

    @Test
    void testSmtpModeFlag() {
        MailService.MailConfig cfg = new MailService.MailConfig();
        cfg.smtpHost = "smtp.example.com";
        cfg.smtpPort = 587;
        cfg.dataDir = tmp.toString();
        MailService svc = new MailService(cfg, tmp.toString());
        assertEquals("smtp", svc.config.mode());
        assertTrue(svc.describe().contains("SMTP"));
    }

    // ── email 工具类 (LLM 调用面) ─────────────────────────

    private ToolRegistry.ToolKit mailToolkit(AgentRole sender, RolePool pool, MailService svc) {
        ToolRegistry.ToolKit tk = ToolkitBridge.toLegacy(new Email(sender, svc));
        sender.setPool(pool);
        return tk;
    }

    @Test
    void testSendEmailToolByPersonName() {
        RolePool pool = new RolePool();
        AgentRole a = role("郭晓东", "tester_1", "测试组");
        AgentRole b = role("王建国", "architect", "架构与版本组");
        pool.addRole(a);
        pool.addRole(b);
        MailService svc = service("company.com");
        ToolRegistry.ToolKit tk = mailToolkit(a, pool, svc);
        String result = tk.getTool("send_email").handler.handle(
                Map.of("to", "王建国", "subject", "跨组沟通", "body", "用邮件联系架构师"));
        assertTrue(result.contains("邮件已发送给"));
        List<MailService.MailMessage> inbox = svc.inbox(svc.emailFor(b), null);
        assertEquals(1, inbox.size());
        assertEquals("郭晓东", inbox.get(0).senderName);
    }

    @Test
    void testSendEmailUnknownRecipient() {
        RolePool pool = new RolePool();
        AgentRole a = role("郭晓东", "tester_1", "测试组");
        pool.addRole(a);
        MailService svc = service("company.com");
        ToolRegistry.ToolKit tk = mailToolkit(a, pool, svc);
        String result = tk.getTool("send_email").handler.handle(
                Map.of("to", "不存在的同事", "subject", "x", "body", "y"));
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("mail_address_book"));
    }

    @Test
    void testReadAndOpenMailTools() {
        RolePool pool = new RolePool();
        AgentRole a = role("郭晓东", "tester_1", "测试组");
        AgentRole b = role("王建国", "architect", "架构与版本组");
        pool.addRole(a);
        pool.addRole(b);
        MailService svc = service("company.com");
        ToolRegistry.ToolKit tkA = mailToolkit(a, pool, svc);
        ToolRegistry.ToolKit tkB = mailToolkit(b, pool, svc);
        svc.send(svc.emailFor(a), a.name, List.of(svc.emailFor(b)), "联调说明", "明天下午联调", null);

        String listed = tkB.getTool("read_mail").handler.handle(Map.of("limit", 5));
        assertTrue(listed.contains("联调说明"));
        assertTrue(listed.contains("未读"));
        assertTrue(listed.contains("id="));

        String opened = tkB.getTool("open_mail").handler.handle(
                Map.of("message_id", svc.inbox(svc.emailFor(b), null).get(0).messageId));
        assertTrue(opened.contains("明天下午联调"));
        assertEquals(0, svc.unreadCount(svc.emailFor(b)));

        String bad = tkB.getTool("open_mail").handler.handle(Map.of("message_id", "nope"));
        assertTrue(bad.contains("Error"));
    }

    @Test
    void testAddressBookGroupsAndEmails() {
        RolePool pool = new RolePool();
        AgentRole a = role("顾承宇", "frontend_dev_1", "前端开发组");
        AgentRole b = role("陈思远", "frontend_lead", "前端开发组");
        AgentRole c = role("林总", "CEO", "领导组");
        pool.addRole(a);
        pool.addRole(b);
        pool.addRole(c);
        MailService svc = service("company.com");
        ToolRegistry.ToolKit tk = mailToolkit(a, pool, svc);

        String book = tk.getTool("mail_address_book").handler.handle(Map.of());
        assertTrue(book.contains("【前端开发组】"));
        assertTrue(book.contains("【领导组】"));
        assertTrue(book.contains("顾承宇 <guchengyu@company.com>"));
        assertTrue(book.contains("陈思远 <chensiyuan@company.com>"));
        assertTrue(book.contains("林总 <linzong@company.com>"));
        assertTrue(!book.contains("frontend_dev_1"));

        String filtered = tk.getTool("mail_address_book").handler.handle(Map.of("group", "前端开发组"));
        assertTrue(!filtered.contains("【领导组】"));
        String notFound = tk.getTool("mail_address_book").handler.handle(Map.of("group", "不存在组"));
        assertTrue(notFound.contains("Error"));
    }
}
