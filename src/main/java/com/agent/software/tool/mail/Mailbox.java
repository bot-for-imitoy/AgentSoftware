package com.agent.software.tool.mail;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.kernel.Ids.MailId;

import java.util.List;
import java.util.Optional;

/**
 * 公司邮箱能力（虚拟邮箱；可选真实 SMTP）。
 *
 * <p>唯一地址分配权威：角色地址只由 {@link #addressOf(RoleSpec)} 决定，
 * 避免 master 中 {@code MailService.emailFor} 与 adapter 各算一份。
 */
public interface Mailbox {

    /** 该角色的公司邮箱地址。 */
    String addressOf(RoleSpec spec);

    MailId send(OutgoingMail mail);

    List<MailMessage> inbox(String address, int limit);

    int unreadCount(String address);

    /** 读信并标记已读。 */
    Optional<MailMessage> read(String address, MailId id);

    /** 注册投递监听：邮箱落信 → 转成 NEW_MAIL 事件（由 bootstrap 接线）。 */
    void onDelivery(DeliveryListener listener);

    record OutgoingMail(String fromAddress, String fromName, List<String> to,
                        List<String> cc, String subject, String body) {
    }

    @FunctionalInterface
    interface DeliveryListener {
        void delivered(MailMessage message, String recipientAddress);
    }
}
