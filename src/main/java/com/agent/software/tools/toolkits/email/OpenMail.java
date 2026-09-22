package com.agent.software.tools.toolkits.email;

import com.agent.software.role.Role;
import com.agent.software.services.MailMessage;
import com.agent.software.services.MailService;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** open_mail：按 message_id 查看邮件全文。 */
public class OpenMail extends Tool {

    private final Role role;
    private final MailService mail;

    public OpenMail(Role role, MailService mail) {
        this.role = role;
        this.mail = mail;
    }

    @Override
    public String getToolName() {
        return "open_mail";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("message_id", "id from read_mail");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Open a mail by its message_id.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || mail == null) {
            return "open_mail error: mail service unavailable";
        }
        String id = args.get("message_id") == null ? "" : String.valueOf(args.get("message_id"));
        MailMessage m = mail.read(mail.getAddress(role.roleId), id);
        return m == null ? "open_mail: no mail with id " + id : m.fullText();
    }
}
