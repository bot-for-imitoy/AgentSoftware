package com.agent.software.tools.toolkits.email;

import com.agent.software.role.AgentRole;

import com.agent.software.services.MailService;
import com.agent.software.tools.Toolkit;

/**
 * Company email toolkit (Email Toolkit) - employee mail send/receive:
 * send_email / read_mail / open_mail / mail_address_book.
 */
public class Email extends Toolkit {

    private final AgentRole agentRole;
    private final MailService mailService;

    public Email(AgentRole agentRole, MailService mailService) {
        this.agentRole = agentRole;
        this.mailService = mailService != null ? mailService : MailService.getMailService();
        addTool(new SendEmail(agentRole, this.mailService));
        addTool(new ReadMail(agentRole, this.mailService));
        addTool(new OpenMail(agentRole, this.mailService));
        addTool(new MailAddressBook(agentRole, this.mailService));
    }

    @Override
    public String getDescription(){
        return "Company email toolkit: send emails, check the inbox, open emails, and view the address book";
    }

}
