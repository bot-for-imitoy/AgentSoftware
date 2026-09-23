package com.agent.software.tools.toolkits.email;

import com.agent.software.role.Role;
import com.agent.software.services.MailMessage;
import com.agent.software.services.MailService;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * read_mail：查看自己收件箱。
 *
 * <p>{@code unread_only=true} 时只列出未读邮件 —— 收到 NEW_MAIL 通知后用它，
 * 不必把整个收件箱（含大量已读回执）重新扫一遍。
 */
public class ReadMail extends Tool {

    private final Role role;
    private final MailService mail;

    public ReadMail(Role role, MailService mail) {
        this.role = role;
        this.mail = mail;
    }

    @Override
    public String getToolName() {
        return "read_mail";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("limit", Map.of("type", "integer", "description", "how many recent mails (default 10)"));
        schema.put("unread_only", Map.of("type", "boolean",
                "description", "list only unread mail (default false)"));
        return schema;
    }

    @Override
    public String getDescription() {
        return "List your inbox, newest last. Use unread_only=true to see only new mail.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || mail == null) {
            return "read_mail error: mail service unavailable";
        }
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 10;
        boolean unreadOnly = args.get("unread_only") instanceof Boolean b && b;
        String address = mail.getAddress(role.roleId);

        List<MailMessage> inbox = new ArrayList<>();
        for (MailMessage m : mail.inbox(address, null)) {
            if (unreadOnly && m.read) {
                continue;
            }
            inbox.add(m);
        }
        if (limit > 0 && inbox.size() > limit) {
            inbox = new ArrayList<>(inbox.subList(inbox.size() - limit, inbox.size()));
        }

        StringBuilder sb = new StringBuilder("read_mail"
                + (unreadOnly ? " (unread only)" : "") + ": " + inbox.size() + " mail(s), unread "
                + mail.unreadCount(address) + "\n");
        for (MailMessage m : inbox) {
            sb.append("  - [").append(m.read ? "read" : "NEW").append("] ").append(m.messageId)
                    .append(" | ").append(m.senderName).append(" <").append(m.senderEmail).append(">")
                    .append(" | ").append(m.subject).append('\n');
        }
        if (inbox.isEmpty()) {
            sb.append(unreadOnly ? "  (no unread mail)" : "  (empty)");
        }
        return sb.toString();
    }
}
