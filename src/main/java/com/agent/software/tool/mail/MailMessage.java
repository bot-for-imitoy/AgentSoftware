package com.agent.software.tool.mail;

import com.agent.software.kernel.Ids.MailId;

import java.time.Instant;
import java.util.List;

/** 一封公司邮件。纯数据，不认识 SMTP 与磁盘。 */
public record MailMessage(MailId id, String fromEmail, String fromName,
                          List<String> to, List<String> cc, String subject, String body,
                          Instant sentAt, boolean read) {

    /** 收件箱预览用的一行摘要。 */
    public String preview() {
        throw new UnsupportedOperationException("skeleton");
    }
}
