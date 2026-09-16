package com.agent.software.adapters.mail;

import com.agent.software.adapters.config.AppConfig;
import com.agent.software.ports.Mailbox;

/**
 * 可选的真实 SMTP 发送器：把公司邮箱的对外邮件经 jakarta.mail 投递。
 */
public final class SmtpSender {

    /** 绑定 SMTP 服务器配置。 */
    public SmtpSender(AppConfig.Mail.Smtp config) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 发送一封对外邮件。 */
    public void send(Mailbox.OutgoingMail mail) {
        throw new UnsupportedOperationException("skeleton");
    }
}
