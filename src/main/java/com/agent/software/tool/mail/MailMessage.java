package com.agent.software.tool.mail;

import com.agent.software.kernel.Ids.MailId;
import com.agent.software.kernel.Text;

import java.time.Instant;
import java.util.List;

/** 一封公司邮件。纯数据，不认识 SMTP 与磁盘。 */
public record MailMessage(MailId id, String fromEmail, String fromName,
                          List<String> to, List<String> cc, String subject, String body,
                          Instant sentAt, boolean read) {

    /**
     * 收件箱预览用的一行摘要。
     *
     * <p>格式固定为 {@code [未读]/[已读] 发件人 主题：正文前 60 字}；正文与主题里的换行、
     * 连续空白先折成单空格，保证一行一条、列表不会被多行正文撑爆。
     */
    public String preview() {
        String flag = read ? "[已读]" : "[未读]";
        String who = Text.squashWhitespace(fromName);
        String topic = Text.squashWhitespace(subject);
        String summary = Text.truncate(Text.squashWhitespace(body), 60);
        return flag + " " + who + " " + topic + "：" + summary;
    }
}
