package com.agent.software.tools.toolkits.email;

import com.agent.software.role.Role;
import com.agent.software.services.MailService;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** mail_address_book：公司通讯录（大组同事 + 客户）。 */
public class MailAddressBook extends Tool {

    private final Role role;
    private final MailService mail;

    public MailAddressBook(Role role, MailService mail) {
        this.role = role;
        this.mail = mail;
    }

    @Override
    public String getToolName() {
        return "mail_address_book";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String getDescription() {
        return "List company addresses: active colleagues and the client.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null || mail == null) {
            return "mail_address_book error: unavailable";
        }
        StringBuilder sb = new StringBuilder("mail_address_book:\n");
        sb.append("  - Client A <").append(mail.getClientAddress()).append(">\n");
        for (Role r : role.getSystem().getRolePool().all()) {
            sb.append("  - ").append(r.name).append(" (").append(r.roleId).append(", ").append(r.group)
                    .append(") <").append(mail.getAddress(r.roleId)).append(">\n");
        }
        return sb.toString();
    }
}
