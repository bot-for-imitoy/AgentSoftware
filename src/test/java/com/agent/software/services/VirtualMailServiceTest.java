package com.agent.software.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 虚拟邮箱：发送、收件箱、未读数、已读、投递回调。 */
class VirtualMailServiceTest {

    @Test
    void sendDeliverRead(@TempDir Path dir) {
        VirtualMailService mail = new VirtualMailService(new MailConfig(), dir);
        AtomicInteger delivered = new AtomicInteger();
        mail.setDeliveryListener((message, recipient) -> delivered.incrementAndGet());

        String ceo = mail.getAddress("CEO");
        String coo = mail.getAddress("COO");
        assertEquals("ceo@agentsoftware.local", ceo);
        assertNotNull(mail.getClientAddress());

        String result = mail.send(ceo, List.of(coo), List.of(), "Budget", "please review");
        assertTrue(result.startsWith("mail sent"), result);

        assertEquals(1, mail.inbox(coo, 10).size());
        assertEquals(1, mail.unreadCount(coo));
        assertEquals(1, delivered.get());

        MailMessage m = mail.inbox(coo, 10).get(0);
        assertEquals("Budget", m.subject);
        assertEquals(ceo, m.senderEmail);

        MailMessage opened = mail.read(coo, m.messageId);
        assertNotNull(opened);
        assertTrue(opened.read);
        assertEquals(0, mail.unreadCount(coo));
    }

    @Test
    void sendingWithoutRecipientsIsRejected() {
        VirtualMailService mail = new VirtualMailService(new MailConfig(), null);
        assertTrue(mail.send("a@x", List.of(), List.of(), "s", "b").startsWith("mail error"));
    }
}
