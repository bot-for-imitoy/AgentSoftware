package com.agent.software.tools.toolkits.email;

import com.agent.software.role.AgentRole;

import com.agent.software.services.MailService;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * open_mail — 打开一封邮件查看完整内容 (自动标记为已读).
 * message_id 来自 read_mail 列表.
 */
public class OpenMail extends Tool {

    private final AgentRole agentRole;
    private final MailService mailService;

    public OpenMail(AgentRole agentRole, MailService mailService) {
        super();
        this.agentRole = agentRole;
        this.mailService = mailService;
    }

    @Override
    public String getToolName() {
        return "open_mail";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("message_id", "The mail id (returned by read_mail).");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object omessageId = args.get("message_id");
        if (!(omessageId instanceof String)) {
            return omessageId == null
                    ? "open_mail: Error: needs message_id"
                    : "open_mail: Error: message_id is not a string";
        }
        String messageId = ((String) omessageId).strip();
        if (messageId.isEmpty()) {
            return "open_mail: Error: needs message_id";
        }
        MailService.MailMessage msg = mailService.read(mailService.emailFor(agentRole), messageId);
        if (msg == null) {
            return "open_mail: Error: mail not found: " + messageId
                    + ". Please call read_mail first to view mails in the current inbox.";
        }
        return msg.fullText();
    }
}
