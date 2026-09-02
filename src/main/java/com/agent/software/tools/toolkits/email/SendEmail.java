package com.agent.software.tools.toolkits.email;

import com.agent.software.role.AgentRole;

import com.agent.software.role.RolePool;
import com.agent.software.services.MailService;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * send_email - send company mail to colleagues (the way employees communicate formally/across groups).
 * to takes a colleague name (see the mail_address_book directory) or a full email address; can be sent to multiple people at once.
 */
public class SendEmail extends Tool {

    private final AgentRole agentRole;
    private final MailService mailService;

    public SendEmail(AgentRole agentRole, MailService mailService) {
        super();
        this.agentRole = agentRole;
        this.mailService = mailService;
    }

    @Override
    public String getToolName() {
        return "send_email";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("to", "Recipient: colleague name or email, comma separated for multiple.");
        schema.put("subject", "Email subject (one sentence).");
        schema.put("body", "Email body (the more detailed the better).");
        schema.put("cc", "(Optional) CC: name or email, comma separated.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object oto = args.get("to");
        Object osubject = args.get("subject");
        Object obody = args.get("body");
        Object occ = args.get("cc");
        if (!(oto instanceof String) || String.valueOf(oto).strip().isEmpty()) {
            return oto == null
                    ? "send_email: Error: needs 'to' (recipient)"
                    : "send_email: Error: 'to' is not a string";
        }
        String subject = osubject instanceof String s ? s.strip() : "";
        String body = obody instanceof String s ? s : "";
        if (subject.isEmpty() && body.strip().isEmpty()) {
            return "send_email: Error: at least one of 'subject' or 'body' must be provided.";
        }
        RolePool pool = agentRole.pool();
        List<String> failed = new ArrayList<>();
        List<String> to = resolveRecipients(pool, oto, failed);
        List<String> cc = new ArrayList<>();
        if (occ != null) {
            List<String> ccFailed = new ArrayList<>();
            cc = resolveRecipients(pool, occ, ccFailed);
            failed.addAll(ccFailed);
        }
        if (to.isEmpty()) {
            return "send_email: Error: could not resolve recipients: " + String.join(", ", failed.isEmpty() ? List.of("(empty)") : failed)
                    + ". Please call mail_address_book first to look up member names/emails.";
        }
        String senderEmail = mailService.emailFor(agentRole);
        String result = mailService.send(senderEmail, agentRole.name, to, subject, body, cc);
        if (!failed.isEmpty()) {
            result += " Note: the following recipients were not found and were not sent: " + String.join(", ", failed);
        }
        agentRole.journal("Sent mail: \"" + subject + "\" -> " + String.join(", ", to));
        return "send_email: " + result;
    }

    /** Resolve recipients in batch (a mix of names or emails, supports comma-separated strings/lists). */
    private List<String> resolveRecipients(RolePool pool, Object values, List<String> failed) {
        List<String> parts = new ArrayList<>();
        if (values instanceof String s) {
            for (String v : s.split(",")) {
                if (!v.strip().isEmpty()) {
                    parts.add(v.strip());
                }
            }
        } else if (values instanceof List<?> list) {
            for (Object v : list) {
                if (String.valueOf(v).strip().isEmpty()) {
                    continue;
                }
                parts.add(String.valueOf(v).strip());
            }
        } else if (values != null) {
            parts.add(String.valueOf(values).strip());
        }
        List<String> emails = new ArrayList<>();
        for (String p : parts) {
            String addr = resolveAddress(pool, p);
            if (!addr.isEmpty()) {
                emails.add(addr);
            } else {
                failed.add(p);
            }
        }
        return emails;
    }

    /** Resolve a name/email to an email address; returns an empty string if it cannot be resolved. */
    private String resolveAddress(RolePool pool, String value) {
        String v = (value == null ? "" : value).strip();
        if (v.isEmpty()) {
            return "";
        }
        if (v.contains("@")) {
            return v;
        }
        if (pool == null) {
            return "";
        }
        AgentRole role = pool.getRoleByName(v);
        if (role == null) {
            return "";
        }
        return mailService.emailFor(role);
    }
}
