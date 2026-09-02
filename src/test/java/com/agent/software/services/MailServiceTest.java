package com.agent.software.services;

import com.agent.software.role.AgentRole;
import com.agent.software.role.RoleLoader;
import com.agent.software.role.RolePool;
import com.agent.software.tools.toolkits.email.Email;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Company mail system tests (the Java counterpart of the Python test_mail.py, virtual mode).
 * The real SMTP path in Java depends on jakarta.mail and is not mocked in unit tests.
 */
class MailServiceTest {

    @TempDir
    Path tmp;

    /**
     * Test role constructor: when the name and id match the template, the username is taken
     * directly from the template JSON (the old PinyinMap's pinyin derivation now lives in its username field).
     */
    private static AgentRole role(String name, String roleId, String group) {
        AgentRole.Builder b = AgentRole.builder().name(name).roleId(roleId).group(group);
        if (RoleLoader.TEMPLATES.containsKey(roleId)) {
            AgentRole t = RoleLoader.getTemplate(roleId);
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

    // ── Email address assignment ─────────────────────────────

    @Test
    void testEmailAddressFromUsernameAndSuffix() {
        AgentRole r = role("Guo Xiaodong", "tester_1", "");
        assertEquals("guoxiaodong@example.com", service("example.com").emailFor(r));
        assertEquals("guoxiaodong@company.cn", service("company.cn").emailFor(r));
    }

    @Test
    void testEmailExplicitFieldWins() {
        AgentRole r = role("Guo Xiaodong", "tester_1", "");
        r.email = "dx.guo@corp.cn";
        assertEquals("dx.guo@corp.cn", service("company.com").emailFor(r));
    }

    // ── Virtual delivery round trip ───────────────────────────

    @Test
    void testVirtualSendAndInbox() {
        MailService svc = service("company.com");
        AgentRole a = role("Guo Xiaodong", "tester_1", "Testing Group");
        AgentRole b = role("Wang Jianguo", "architect", "Architecture & Release Group");
        String result = svc.send(svc.emailFor(a), a.name, List.of(svc.emailFor(b)),
                "Test report", "Found a login page bug; see the attachment for details.", null);
        assertTrue(result.contains("Email sent to"));
        assertTrue(result.contains("virtual mailbox delivery"));

        List<MailService.MailMessage> inbox = svc.inbox(svc.emailFor(b), null);
        assertEquals(1, inbox.size());
        assertEquals("Test report", inbox.get(0).subject);
        assertEquals("Guo Xiaodong", inbox.get(0).senderName);
        assertEquals(1, svc.unreadCount(svc.emailFor(b)));

        MailService.MailMessage msg = svc.read(svc.emailFor(b), inbox.get(0).messageId);
        assertTrue(msg != null && msg.body.contains("login page bug"));
        assertEquals(0, svc.unreadCount(svc.emailFor(b)));
    }

    @Test
    void testSendToMultipleAndCc() {
        MailService svc = service("company.com");
        AgentRole a = role("Lin Zong", "CEO", "Leadership Group");
        AgentRole b = role("Chen Zong", "COO", "Leadership Group");
        AgentRole c = role("Wang Renshi", "HR", "Leadership Group");
        svc.send(svc.emailFor(a), a.name, List.of(svc.emailFor(b)),
                "Weekly meeting agenda", "We will meet tomorrow morning.", List.of(svc.emailFor(c)));
        assertEquals(1, svc.inbox(svc.emailFor(b), null).size());
        assertEquals(1, svc.inbox(svc.emailFor(c), null).size());
        // the recipient reading the mail does not affect the CC recipient
        svc.read(svc.emailFor(b), svc.inbox(svc.emailFor(b), null).get(0).messageId);
        assertEquals(0, svc.unreadCount(svc.emailFor(b)));
        assertEquals(1, svc.unreadCount(svc.emailFor(c)));
    }

    @Test
    void testPersistenceRoundtrip() {
        MailService svc = service("company.com");
        AgentRole a = role("Guo Xiaodong", "tester_1", "");
        AgentRole b = role("Wang Jianguo", "architect", "");
        svc.send(svc.emailFor(a), a.name, List.of(svc.emailFor(b)), "Archive", "Can I still see this after a restart?", null);
        MailService svc2 = service("company.com");
        List<MailService.MailMessage> inbox = svc2.inbox(svc.emailFor(b), null);
        assertEquals(1, inbox.size());
        assertEquals("Archive", inbox.get(0).subject);
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

    // ── email toolkit (LLM call surface) ──────────────────────

    private Email mailToolkit(AgentRole sender, RolePool pool, MailService svc) {
        sender.setPool(pool);
        return new Email(sender, svc);
    }

    @Test
    void testSendEmailToolByPersonName() {
        RolePool pool = new RolePool();
        AgentRole a = role("Guo Xiaodong", "tester_1", "Testing Group");
        AgentRole b = role("Wang Jianguo", "architect", "Architecture & Release Group");
        pool.addRole(a);
        pool.addRole(b);
        MailService svc = service("company.com");
        Email email = mailToolkit(a, pool, svc);
        String result = email.trigger("send_email",
                Map.of("to", "Wang Jianguo", "subject", "Cross-team communication", "body", "Contacting the architect via email"));
        assertTrue(result.contains("Email sent to"));
        List<MailService.MailMessage> inbox = svc.inbox(svc.emailFor(b), null);
        assertEquals(1, inbox.size());
        assertEquals("Guo Xiaodong", inbox.get(0).senderName);
    }

    @Test
    void testSendEmailUnknownRecipient() {
        RolePool pool = new RolePool();
        AgentRole a = role("Guo Xiaodong", "tester_1", "Testing Group");
        pool.addRole(a);
        MailService svc = service("company.com");
        Email email = mailToolkit(a, pool, svc);
        String result = email.trigger("send_email",
                Map.of("to", "Nonexistent colleague", "subject", "x", "body", "y"));
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("mail_address_book"));
    }

    @Test
    void testReadAndOpenMailTools() {
        RolePool pool = new RolePool();
        AgentRole a = role("Guo Xiaodong", "tester_1", "Testing Group");
        AgentRole b = role("Wang Jianguo", "architect", "Architecture & Release Group");
        pool.addRole(a);
        pool.addRole(b);
        MailService svc = service("company.com");
        Email emailA = mailToolkit(a, pool, svc);
        Email emailB = mailToolkit(b, pool, svc);
        svc.send(svc.emailFor(a), a.name, List.of(svc.emailFor(b)), "Integration session notes", "Integration tomorrow afternoon", null);

        String listed = emailB.trigger("read_mail", Map.of("limit", 5));
        assertTrue(listed.contains("Integration session notes"));
        assertTrue(listed.contains("unread"));
        assertTrue(listed.contains("id="));

        String opened = emailB.trigger("open_mail",
                Map.of("message_id", svc.inbox(svc.emailFor(b), null).get(0).messageId));
        assertTrue(opened.contains("Integration tomorrow afternoon"));
        assertEquals(0, svc.unreadCount(svc.emailFor(b)));

        String bad = emailB.trigger("open_mail", Map.of("message_id", "nope"));
        assertTrue(bad.contains("Error"));
    }

    @Test
    void testAddressBookGroupsAndEmails() {
        RolePool pool = new RolePool();
        AgentRole a = role("Gu Chengyu", "frontend_dev_1", "Frontend Development Group");
        AgentRole b = role("Chen Siyuan", "frontend_lead", "Frontend Development Group");
        AgentRole c = role("Lin Zong", "CEO", "Leadership Group");
        pool.addRole(a);
        pool.addRole(b);
        pool.addRole(c);
        MailService svc = service("company.com");
        Email email = mailToolkit(a, pool, svc);

        String book = email.trigger("mail_address_book", Map.of());
        assertTrue(book.contains("[Frontend Development Group]"));
        assertTrue(book.contains("[Leadership Group]"));
        assertTrue(book.contains("Gu Chengyu <guchengyu@company.com>"));
        assertTrue(book.contains("Chen Siyuan <chensiyuan@company.com>"));
        assertTrue(book.contains("Lin Zong <linzong@company.com>"));
        assertTrue(!book.contains("frontend_dev_1"));

        String filtered = email.trigger("mail_address_book", Map.of("group", "Frontend Development Group"));
        assertTrue(!filtered.contains("[Leadership Group]"));
        String notFound = email.trigger("mail_address_book", Map.of("group", "Nonexistent Group"));
        assertTrue(notFound.contains("Error"));
    }
}
