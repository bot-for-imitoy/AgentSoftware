package com.agent.software.ports;

import com.agent.software.domain.RoleSpec;

import java.util.List;
import java.util.Optional;

/** Company-mail port (virtual mailbox or real SMTP, decided by the adapter). */
public interface MailPort {

    /** The mailbox address assigned to a role. */
    String addressFor(RoleSpec role);

    /** Deliver a draft; returns a human-readable delivery summary. */
    String send(MailDraft draft);

    List<MailMessage> inbox(String address, int limit, boolean unreadOnly);

    /** Open one message, marking it read. */
    Optional<MailMessage> open(String address, String messageId);

    /** {@code "virtual"} or {@code "smtp"}. */
    String mode();

    record MailDraft(String senderEmail, String senderName, List<String> to,
                     String subject, String body, List<String> cc) {
        public MailDraft {
            senderEmail = senderEmail == null ? "" : senderEmail;
            senderName = senderName == null ? "" : senderName;
            to = to == null ? List.of() : List.copyOf(to);
            subject = subject == null ? "" : subject;
            body = body == null ? "" : body;
            cc = cc == null ? List.of() : List.copyOf(cc);
        }
    }

    record MailMessage(String id, String senderEmail, String senderName, String subject,
                       String body, List<String> recipients, List<String> cc,
                       double timestamp, boolean read) {
        public MailMessage {
            id = id == null ? "" : id;
            senderEmail = senderEmail == null ? "" : senderEmail;
            senderName = senderName == null ? "" : senderName;
            subject = subject == null ? "" : subject;
            body = body == null ? "" : body;
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
            cc = cc == null ? List.of() : List.copyOf(cc);
        }
    }
}
