package com.agent.software.adapters.mail;

import com.agent.software.adapters.config.AppConfig;
import com.agent.software.adapters.config.AppPaths;
import com.agent.software.kernel.Ids.MailId;
import com.agent.software.model.MailMessage;
import com.agent.software.model.RoleSpec;
import com.agent.software.ports.Mailbox;

import java.util.List;
import java.util.Optional;

/**
 * 落盘的公司虚拟邮箱：地址由配置后缀推导，邮件按收件人存放于数据目录。
 */
public final class FileMailbox implements Mailbox {

    /** 绑定邮件配置与数据路径。 */
    public FileMailbox(AppConfig.Mail config, AppPaths paths) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public String addressOf(RoleSpec spec) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public MailId send(OutgoingMail mail) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<MailMessage> inbox(String address, int limit) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public int unreadCount(String address) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public Optional<MailMessage> read(String address, MailId id) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void onDelivery(DeliveryListener listener) {
        throw new UnsupportedOperationException("skeleton");
    }
}
