package com.agent.software.tools.toolkits.email;

import com.agent.software.role.Role;

import com.agent.software.services.MailService;
import com.agent.software.tools.Toolkit;

/**
 * Company email toolkit (Email Toolkit) - employee mail send/receive:
 * send_email / read_mail / open_mail / mail_address_book.
 */
public class Email extends Toolkit {

    private final Role role;
    private final MailService mailService;

    public Email(Role role, MailService mailService) {
        this.role = role;
        this.mailService = mailService != null ? mailService : MailService.getMailService();
        addTool(new SendEmail(role, this.mailService));
        addTool(new ReadMail(role, this.mailService));
        addTool(new OpenMail(role, this.mailService));
        addTool(new MailAddressBook(role, this.mailService));
    }

    @Override
    public String getDescription(){
        return "Company email toolkit: send emails, check the inbox, open emails, and view the address book";
    }

}
