package com.agent.software.adapters.mail;

import com.agent.software.domain.RoleSpec;
import com.agent.software.ports.MailPort;
import com.agent.software.services.MailService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link MailPort} backed by the existing {@code MailService} (virtual mailbox or
 * real SMTP). Address allocation is reimplemented on {@link RoleSpec} so the port
 * does not depend on the legacy {@code AgentRole}.
 */
public final class MailServiceAdapter implements MailPort {

    private final MailService service;

    public MailServiceAdapter(MailService service) {
        if (service == null) {
            throw new IllegalArgumentException("service must not be null");
        }
        this.service = service;
    }

    public MailService service() {
        return service;
    }

    @Override
    public String addressFor(RoleSpec role) {
        if (role == null) {
            return "";
        }
        if (!role.email().isBlank()) {
            return role.email();
        }
        String local = !role.username().isBlank() ? role.username()
                : (role.id().value().isBlank() ? "agent" : role.id().value());
        return (local + "@" + service.config.suffix).toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String send(MailDraft draft) {
        return service.send(draft.senderEmail(), draft.senderName(), draft.to(),
                draft.subject(), draft.body(), draft.cc());
    }

    @Override
    public List<MailMessage> inbox(String address, int limit, boolean unreadOnly) {
        List<MailService.MailMessage> raw = service.inbox(address, null);
        List<MailMessage> out = new ArrayList<>();
        for (MailService.MailMessage m : raw) {
            if (unreadOnly && m.read) {
                continue;
            }
            if (limit > 0 && out.size() >= limit) {
                break;
            }
            out.add(convert(m));
        }
        return out;
    }

    @Override
    public Optional<MailMessage> open(String address, String messageId) {
        MailService.MailMessage m = service.read(address, messageId);
        return m == null ? Optional.empty() : Optional.of(convert(m));
    }

    @Override
    public String mode() {
        return service.config.mode();
    }

    private static MailMessage convert(MailService.MailMessage m) {
        return new MailMessage(m.messageId, m.senderEmail, m.senderName, m.subject, m.body,
                m.recipients, m.cc, m.timestamp, m.read);
    }
}
