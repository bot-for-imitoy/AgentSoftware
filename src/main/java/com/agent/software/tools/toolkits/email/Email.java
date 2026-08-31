package com.agent.software.tools.toolkits.email;

import com.agent.software.role.AgentRole;
import com.maf.scheduler.core.MailService;
import com.agent.software.tools.Toolkit;

/**
 * 公司邮件工具类 (Email Toolkit) — 员工邮件收发:
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
        return "公司邮件工具类: 发送邮件, 查看收件箱, 打开邮件, 查看通讯录";
    }

}
