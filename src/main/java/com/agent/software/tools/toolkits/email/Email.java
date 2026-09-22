package com.agent.software.tools.toolkits.email;

import com.agent.software.role.Role;
import com.agent.software.services.MailService;
import com.agent.software.tools.Toolkit;

/** 邮件工具包：send_email / read_mail / open_mail / mail_address_book。 */
public class Email extends Toolkit {

    public Email(Role role, MailService mail) {
        addTool(new SendEmail(role, mail));
        addTool(new ReadMail(role, mail));
        addTool(new OpenMail(role, mail));
        addTool(new MailAddressBook(role, mail));
    }

    @Override
    public String getDescription() {
        return "Company email: send_email / read_mail / open_mail / mail_address_book";
    }
}
