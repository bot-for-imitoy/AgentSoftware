package com.agent.software;

import com.agent.software.core.Types;
import com.agent.software.io.StdInput;
import com.agent.software.role.AgentRole;
import com.agent.software.services.MailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * New-mail notification tests: after a mail is delivered to an employee mailbox inside an AgentSystem,
 * the recipient receives a targeted "NEW_MAIL" event that guides it to call read_mail.
 *
 * Roles are loaded from the real role templates (CEO Lin Zong / CTO Gao Yuan / CFO Qian Cai), the mailbox
 * service is the system instance (virtual delivery), and the pool is intentionally NOT started so that
 * queued tasks stay deterministic (no LLM / no worker threads).
 */
class AgentSystemMailNotifyTest {

    @TempDir
    Path tmp;

    private AgentSystem make(Path dir) {
        return new AgentSystem(dir, null, List.of("CEO", "CTO", "CFO"), 30.0, false, new StdInput());
    }

    // ── 1. On-duty recipient gets a "new mail" task guiding read_mail ─

    @Test
    void onDutyRecipientGetsNewMailTask() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentRole ceo = a.getRole("CEO");
        AgentRole cto = a.getRole("CTO");

        String result = a.mailService.send(a.mailService.emailFor(cto), cto.name,
                List.of(a.mailService.emailFor(ceo)), "Architecture sync", "Please review the plan", null);
        assertTrue(result.contains("Email sent to"));

        // exactly one NEW_MAIL event dispatched, targeted at the recipient
        assertEquals(1, a.dispatcher.getStats().get("total_events"));
        assertEquals(1, ceo.queueDepth());
        assertEquals(0, cto.queueDepth());  // the sender is not notified

        AgentRole.Task task = ceo.popTask();
        assertNotNull(task);
        assertTrue(task.description.contains("[email/" + AgentSystem.EVENT_NEW_MAIL + "]"));
        assertTrue(task.description.contains("Gao Yuan"));
        assertTrue(task.description.contains("Architecture sync"));
        assertTrue(task.description.toLowerCase().contains("read_mail"));

        // the mail itself is in the recipient's mailbox and unread
        assertEquals(1, a.mailService.inbox(a.mailService.emailFor(ceo), null).size());
        assertEquals(1, a.mailService.unreadCount(a.mailService.emailFor(ceo)));
    }

    // ── 2. To + CC recipients are each notified ─

    @Test
    void toAndCcRecipientsAreBothNotified() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentRole ceo = a.getRole("CEO");
        AgentRole cto = a.getRole("CTO");
        AgentRole cfo = a.getRole("CFO");

        a.mailService.send(a.mailService.emailFor(ceo), ceo.name,
                List.of(a.mailService.emailFor(cto)), "Budget review", "Please check the numbers.",
                List.of(a.mailService.emailFor(cfo)));

        // one targeted NEW_MAIL event per recipient (To and CC each get their own)
        assertEquals(2, a.dispatcher.getStats().get("total_events"));
        assertEquals(1, cto.queueDepth());
        assertEquals(1, cfo.queueDepth());
        assertEquals(0, ceo.queueDepth());
        assertTrue(cto.popTask().description.contains("read_mail"));
        assertTrue(cfo.popTask().description.contains("read_mail"));
    }

    // ── 3. A resting (OFF_DUTY) recipient is not disturbed, but the delivery still happens ─

    @Test
    void restingRecipientIsNotDisturbed() throws Exception {
        AgentSystem a = make(tmp.resolve("a"));
        AgentRole ceo = a.getRole("CEO");
        AgentRole cto = a.getRole("CTO");
        ceo.setState(Types.AgentState.OFF_DUTY);

        a.mailService.send(a.mailService.emailFor(cto), cto.name,
                List.of(a.mailService.emailFor(ceo)), "Evening note", "Nothing urgent.", null);

        // the mail is delivered...
        assertEquals(1, a.mailService.inbox(a.mailService.emailFor(ceo), null).size());
        // ...but the off-duty role is not woken (non-urgent targeted events do not disturb rest)
        assertEquals(0, ceo.queueDepth());
        // the skip is recorded in the role journal for later traceability
        Path journal = a.dataDir().resolve("journals").resolve("CEO.md");
        assertTrue(Files.exists(journal));
        assertTrue(Files.readString(journal).contains(AgentSystem.EVENT_NEW_MAIL));
        assertTrue(Files.readString(journal).contains("skipped"));
    }

    // ── 4. Mail to a non-employee address triggers no event at all ─

    @Test
    void externalRecipientTriggersNoEvent() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentRole ceo = a.getRole("CEO");

        a.mailService.send(a.mailService.emailFor(ceo), ceo.name,
                List.of("external@partner-corp.example"), "Hi", "Are you free?", null);

        assertEquals(0, a.dispatcher.getStats().get("total_events"));
        assertEquals(0, a.getRole("CEO").queueDepth());
        assertEquals(0, a.getRole("CTO").queueDepth());
        assertEquals(0, a.getRole("CFO").queueDepth());
    }

    // ── 5. Sending a mail to yourself delivers it but does not notify yourself ─

    @Test
    void selfMailDoesNotNotifySelf() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentRole cto = a.getRole("CTO");

        a.mailService.send(a.mailService.emailFor(cto), cto.name,
                List.of(a.mailService.emailFor(cto)), "Reminder to self", "Buy coffee beans.", null);

        assertEquals(0, a.dispatcher.getStats().get("total_events"));
        assertEquals(0, cto.queueDepth());
        assertEquals(1, a.mailService.inbox(a.mailService.emailFor(cto), null).size());
    }

    // ── 6. A standalone MailService (no AgentSystem) keeps the silent-delivery behavior ─

    @Test
    void standaloneMailServiceHasNoNotification() {
        MailService svc = new MailService(new MailService.MailConfig(), tmp.resolve("mail").toString());
        String result = svc.send("a@company.com", "A", List.of("b@company.com"), "S", "B", null);
        assertTrue(result.contains("Email sent to"));
        assertEquals(1, svc.inbox("b@company.com", null).size());
    }
}
