package com.agent.software.tools.toolkits.email;

import com.agent.software.role.Role;
import com.agent.software.services.MailService;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** send_email：给同事或客户发邮件。 */
public class SendEmail extends Tool {

    private final Role role;
    private final MailService mail;

    public SendEmail(Role role, MailService mail) {
        this.role = role;
        this.mail = mail;
    }

    @Override
    public String getToolName() {
        return "send_email";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("to", "recipient role_id(s) or email address(es), comma separated");
        schema.put("subject", "mail subject");
        schema.put("body", "mail body");
        schema.put("cc", "optional cc, comma separated");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Send a company email to colleagues or the client.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || mail == null) {
            return "send_email error: mail service unavailable";
        }
        List<String> to = resolve(args.get("to"));
        List<String> cc = resolve(args.get("cc"));
        String subject = args.get("subject") == null ? "" : String.valueOf(args.get("subject"));
        String body = args.get("body") == null ? "" : String.valueOf(args.get("body"));
        if (to.isEmpty()) {
            return "send_email error: no recipients";
        }
        String result = mail.send(mail.getAddress(role.roleId), to, cc, subject, body);
        role.journal("Sent mail: \"" + subject + "\" -> " + to);
        return result;
    }

    /** 支持 role_id 或直接邮箱；role_id 会转成公司地址。 */
    private List<String> resolve(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : String.valueOf(raw).split(",")) {
            String v = part.trim();
            if (v.isEmpty()) {
                continue;
            }
            if (v.contains("@")) {
                out.add(v);
            } else if (role.getSystem() != null && role.getSystem().getRolePool().find(v) != null) {
                out.add(mail.getAddress(v));
            } else if ("client".equalsIgnoreCase(v) || "client_a".equalsIgnoreCase(v)) {
                out.add(mail.getClientAddress());
            } else {
                out.add(mail.getAddress(v));
            }
        }
        return out;
    }
}
