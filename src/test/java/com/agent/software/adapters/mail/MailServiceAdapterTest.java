package com.agent.software.adapters.mail;

import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.MailPort;
import com.agent.software.services.MailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailServiceAdapterTest {

    private static RoleSpec spec(String id, String username, String email) {
        return new RoleSpec(RoleId.of(id), "Name " + id, username, 1101, "Title", "", "",
                List.of(), "", "Test Group", email, "local", Payload.empty(), List.of());
    }

    private static MailServiceAdapter adapter(Path dir) {
        MailService.MailConfig config = new MailService.MailConfig();
        config.suffix = "company.com";
        config.smtpHost = "";
        config.dataDir = dir.toString();
        return new MailServiceAdapter(new MailService(config, dir.toString()));
    }

    @Test
    void addressUsesUsernameUnlessExplicitEmailGiven(@TempDir Path dir) {
        MailServiceAdapter adapter = adapter(dir);
        assertEquals("linzong@company.com", adapter.addressFor(spec("CEO", "linzong", "")));
        assertEquals("boss@external.com", adapter.addressFor(spec("CEO", "linzong", "boss@external.com")));
        assertEquals("virtual", adapter.mode());
    }

    @Test
    void sendThenInboxAndOpen(@TempDir Path dir) {
        MailServiceAdapter adapter = adapter(dir);
        String sender = adapter.addressFor(spec("CEO", "linzong", ""));
        String recipient = adapter.addressFor(spec("COO", "chenzong", ""));

        adapter.send(new MailPort.MailDraft(sender, "Lin Zong", List.of(recipient),
                "Plan", "Please review the plan", List.of()));

        List<MailPort.MailMessage> inbox = adapter.inbox(recipient, 10, false);
        assertEquals(1, inbox.size());
        assertEquals("Plan", inbox.get(0).subject());
        assertFalse(inbox.get(0).read());

        assertEquals(1, adapter.inbox(recipient, 10, true).size());
        MailPort.MailMessage opened = adapter.open(recipient, inbox.get(0).id()).orElseThrow();
        assertEquals("Please review the plan", opened.body());
        assertTrue(opened.read());
        assertTrue(adapter.inbox(recipient, 10, true).isEmpty(), "opened mail must count as read");
    }

    @Test
    void limitCapsTheInbox(@TempDir Path dir) {
        MailServiceAdapter adapter = adapter(dir);
        String sender = adapter.addressFor(spec("CEO", "linzong", ""));
        String recipient = adapter.addressFor(spec("COO", "chenzong", ""));
        for (int i = 0; i < 3; i++) {
            adapter.send(new MailPort.MailDraft(sender, "Lin Zong", List.of(recipient),
                    "Subject " + i, "body", List.of()));
        }
        assertEquals(2, adapter.inbox(recipient, 2, false).size());
    }
}
