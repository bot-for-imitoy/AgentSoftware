package com.agent.software.tools.toolkits.email;

import com.agent.software.role.Role;
import com.agent.software.services.MailMessage;
import com.agent.software.services.MailService;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** read_mail：查看自己收件箱。 */
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
        return schema;
    }

    @Override
    public String getDescription() {
        return "List your inbox, newest last.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || mail == null) {
            return "read_mail error: mail service unavailable";
        }
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 10;
        String address = mail.getAddress(role.roleId);
        List<MailMessage> inbox = mail.inbox(address, limit);
        StringBuilder sb = new StringBuilder("read_mail: " + inbox.size() + " mail(s), unread "
                + mail.unreadCount(address) + "\n");
        for (MailMessage m : inbox) {
            sb.append("  - [").append(m.read ? "read" : "NEW").append("] ").append(m.messageId)
                    .append(" | ").append(m.senderName).append(" <").append(m.senderEmail).append(">")
                    .append(" | ").append(m.subject).append('\n');
        }
        if (inbox.isEmpty()) {
            sb.append("  (empty)");
        }
        return sb.toString();
    }
}
