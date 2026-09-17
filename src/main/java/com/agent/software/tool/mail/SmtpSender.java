package com.agent.software.tool.mail;

import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppConfig.Mail;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Text;
import com.agent.software.tool.mail.Mailbox.OutgoingMail;
import com.agent.software.infra.config.AppConfig.Mail.Smtp;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.UnsupportedEncodingException;
import java.util.Properties;

/**
 * 可选的真实 SMTP 发送器：把公司邮箱的对外邮件经 jakarta.mail 投递。
 *
 * <p>只在 {@link Smtp#configured()} 为真时由 {@link FileMailbox} 调用，因此本身不做
 * "未配置就静默跳过"的兜底——直接抛 {@code mail.smtp.unconfigured}，让调用方决定日志级别。
 */
public final class SmtpSender {

    private final AppConfig.Mail.Smtp config;

    /** 绑定 SMTP 服务器配置。 */
    public SmtpSender(AppConfig.Mail.Smtp config) {
        this.config = config;
    }

    /**
     * 发送一封对外邮件。
     *
     * @throws DomainError {@code mail.smtp.unconfigured} 未配置主机；{@code mail.smtp.failed} 发送过程失败
     */
    public void send(Mailbox.OutgoingMail mail) {
        if (config == null || !config.configured()) {
            throw new DomainError("mail.smtp.unconfigured", "未配置 SMTP 主机，无法外发邮件");
        }
        if (mail == null) {
            throw new DomainError("mail.smtp.failed", "邮件内容为空，无法经 SMTP 发送");
        }
        if (mail.to() == null || mail.to().isEmpty()) {
            throw new DomainError("mail.smtp.failed", "邮件没有收件人，无法经 SMTP 发送");
        }
        try {
            Transport.send(build(mail));
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new DomainError("mail.smtp.failed", "SMTP 发送失败: " + e.getMessage(), e);
        }
    }

    /** 把领域邮件装配成 MIME 消息（连接/认证参数全部来自配置）。 */
    private MimeMessage build(Mailbox.OutgoingMail mail)
            throws MessagingException, UnsupportedEncodingException {
        Session session = Session.getInstance(properties(), authenticator());
        MimeMessage mime = new MimeMessage(session);

        String fromAddress = !Text.isBlank(config.from()) ? config.from()
                : (!Text.isBlank(config.user()) ? config.user() : mail.fromAddress());
        mime.setFrom(new InternetAddress(fromAddress, Text.orEmpty(mail.fromName()), "UTF-8"));
        mime.setSubject(Text.orEmpty(mail.subject()), "UTF-8");
        mime.setText(Text.orEmpty(mail.body()), "UTF-8");
        for (String to : mail.to()) {
            if (!Text.isBlank(to)) {
                mime.addRecipient(Message.RecipientType.TO, new InternetAddress(to.trim()));
            }
        }
        if (mail.cc() != null) {
            for (String cc : mail.cc()) {
                if (!Text.isBlank(cc)) {
                    mime.addRecipient(Message.RecipientType.CC, new InternetAddress(cc.trim()));
                }
            }
        }
        return mime;
    }

    /** useSsl → ssl.enable；非 SSL 时对 587 打开 STARTTLS；有用户才启用认证。 */
    private Properties properties() {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.host());
        props.put("mail.smtp.port", String.valueOf(config.port()));
        props.put("mail.smtp.connectiontimeout", "30000");
        props.put("mail.smtp.timeout", "30000");
        if (config.useSsl()) {
            props.put("mail.smtp.ssl.enable", "true");
        } else if (config.port() == 587) {
            props.put("mail.smtp.starttls.enable", "true");
        }
        if (!Text.isBlank(config.user())) {
            props.put("mail.smtp.auth", "true");
        }
        return props;
    }

    /** 有用户名时提供 {@link PasswordAuthentication}，否则匿名会话。 */
    private Authenticator authenticator() {
        if (Text.isBlank(config.user())) {
            return null;
        }
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.user(), Text.orEmpty(config.password()));
            }
        };
    }
}
