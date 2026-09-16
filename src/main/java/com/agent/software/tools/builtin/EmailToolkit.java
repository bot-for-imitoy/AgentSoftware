package com.agent.software.tools.builtin;

import com.agent.software.ports.AgentDirectory;
import com.agent.software.ports.Mailbox;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.ToolContext;
import com.agent.software.tools.spi.Toolkit;

import java.util.List;

/**
 * 邮件工具包（id {@code "email"}），暴露工具：send_email / read_mail / open_mail / mail_address_book。
 */
public final class EmailToolkit implements Toolkit {

    private final Mailbox mail;
    private final AgentDirectory directory;

    public EmailToolkit(Mailbox mail, AgentDirectory directory) {
        this.mail = mail;
        this.directory = directory;
    }

    @Override
    public String id() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Tool> instantiate(ToolContext context) {
        throw new UnsupportedOperationException("skeleton");
    }
}
