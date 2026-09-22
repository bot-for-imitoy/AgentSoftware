package com.agent.software.services;

import java.nio.file.Path;
import java.util.List;

/**
 * 公司邮件服务（抽象）。
 *
 * <p>本轮只实现 {@link VirtualMailService}；{@code SMTPMailService} 后续接入。
 * 投递通过 {@link MailDeliveryListener} 通知上层（NEW_MAIL 事件的接线点），
 * 邮件层本身不依赖事件总线。
 */
public abstract class MailService {

    public interface MailDeliveryListener {
        void onDelivered(MailMessage message, String recipientAddress);
    }

    private MailDeliveryListener listener;

    public static MailService create(MailConfig config, Path dataDir) {
        MailConfig c = config == null ? MailConfig.fromEnv() : config;
        // SMTP 实现后补；目前统一用虚拟邮箱
        return new VirtualMailService(c, dataDir);
    }

    public void setDeliveryListener(MailDeliveryListener listener) {
        this.listener = listener;
    }

    protected void notifyDelivered(MailMessage message, String recipient) {
        if (listener != null) {
            listener.onDelivered(message, recipient);
        }
    }

    public abstract String getAddress(String roleId);

    public abstract String getClientAddress();

    public abstract String send(String from, List<String> to, List<String> cc, String subject, String body);

    public abstract List<MailMessage> inbox(String address, Integer limit);

    public abstract int unreadCount(String address);

    public abstract MailMessage read(String address, String messageId);
}
