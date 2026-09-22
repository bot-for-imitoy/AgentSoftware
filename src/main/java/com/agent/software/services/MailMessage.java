package com.agent.software.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一封公司邮件。 */
public final class MailMessage {

    public String messageId;
    public String senderEmail = "";
    public String senderName = "";
    public List<String> recipients = new ArrayList<>();
    public List<String> cc = new ArrayList<>();
    public String subject = "";
    public String body = "";
    public long timestamp = System.currentTimeMillis();
    public boolean read = false;

    public MailMessage() {
    }

    public MailMessage(String messageId, String senderEmail, String senderName,
                       List<String> recipients, List<String> cc, String subject, String body) {
        this.messageId = messageId;
        this.senderEmail = senderEmail == null ? "" : senderEmail;
        this.senderName = senderName == null ? "" : senderName;
        if (recipients != null) {
            this.recipients = new ArrayList<>(recipients);
        }
        if (cc != null) {
            this.cc = new ArrayList<>(cc);
        }
        this.subject = subject == null ? "" : subject;
        this.body = body == null ? "" : body;
    }

    public Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message_id", messageId);
        m.put("sender_email", senderEmail);
        m.put("sender_name", senderName);
        m.put("recipients", recipients);
        m.put("cc", cc);
        m.put("subject", subject);
        m.put("body", body);
        m.put("timestamp", timestamp);
        m.put("read", read);
        return m;
    }

    public String fullText() {
        return "From: " + senderName + " <" + senderEmail + ">\n"
                + "To: " + String.join(", ", recipients) + "\n"
                + "Subject: " + subject + "\n\n" + body;
    }
}
