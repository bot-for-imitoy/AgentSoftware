package com.agent.software.tools.toolkits.email;

import com.agent.software.role.AgentRole;

import com.agent.software.services.MailService;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * read_mail — 查看自己的公司邮箱收件箱 (新到优先). 返回邮件列表:
 * 发件人/主题/摘要/已读未读/message_id. 用 open_mail 打开某封邮件的完整内容.
 */
public class ReadMail extends Tool {

    private final AgentRole agentRole;
    private final MailService mailService;

    public ReadMail(AgentRole agentRole, MailService mailService) {
        super();
        this.agentRole = agentRole;
        this.mailService = mailService;
    }

    @Override
    public String getToolName() {
        return "read_mail";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("limit", "(Optional) Max number of messages to show (default 10).");
        schema.put("unread_only", "(Optional) Only show unread mails (default false).");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        String email = mailService.emailFor(agentRole);
        int limit = 10;
        Object l = args.get("limit");
        if (l instanceof Integer) {
            limit = (Integer) l;
        } else if (l instanceof Number n) {
            limit = n.intValue();
        } else if (l instanceof String s && s.matches("\\d+")) {
            limit = Integer.parseInt(s);
        }
        boolean unreadOnly = oBool(args.get("unread_only"));
        List<MailService.MailMessage> msgs = mailService.inbox(email, null);
        if (unreadOnly) {
            msgs.removeIf(m -> m.read);
        }
        if (msgs.size() > Math.max(0, limit)) {
            msgs = new ArrayList<>(msgs.subList(0, Math.max(0, limit)));
        }
        if (msgs.isEmpty()) {
            return "read_mail: 收件箱为空 (邮箱 " + email + ").";
        }
        List<String> lines = new ArrayList<>();
        lines.add("read_mail: 收件箱 " + email + " (" + mailService.unreadCount(email) + " 封未读):");
        int i = 1;
        for (MailService.MailMessage m : msgs) {
            lines.add("  " + i + ". " + m.preview());
            i++;
        }
        return String.join("\n", lines);
    }

    private static boolean oBool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s) {
            return s.matches("1|true|yes|on");
        }
        return false;
    }
}
