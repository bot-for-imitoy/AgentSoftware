package com.agent.software.tools.toolkits.email;

import com.agent.software.role.AgentRole;

import com.agent.software.role.RolePool;
import com.agent.software.services.MailService;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mail_address_book - view the company address book: all members listed by group
 * (group name -> name <email> - position). If unsure about the recipient before sending mail, call this tool first.
 */
public class MailAddressBook extends Tool {

    private final AgentRole agentRole;
    private final MailService mailService;

    public MailAddressBook(AgentRole agentRole, MailService mailService) {
        super();
        this.agentRole = agentRole;
        this.mailService = mailService;
    }

    @Override
    public String getToolName() {
        return "mail_address_book";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("group", "(Optional) Only show one group, e.g. 'Frontend Development Group'.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        RolePool pool = agentRole.pool();
        if (pool == null) {
            return "mail_address_book: Error: the current role is not bound to a role pool, so the address book is unavailable.";
        }
        Object ogroup = args.get("group");
        String groupFilter = ogroup instanceof String s ? s.strip() : "";
        Map<String, List<AgentRole>> byGroup = new LinkedHashMap<>();
        for (AgentRole r : pool.allRoles()) {
            String g = (r.group == null ? "" : r.group).strip();
            if (g.isEmpty()) {
                g = "Ungrouped";
            }
            byGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(r);
        }
        List<String> lines = new ArrayList<>();
        lines.add("mail_address_book: company address book (email suffix @" + mailService.config.suffix
                + ", " + pool.allRoles().size() + " people):");
        List<String> groups = new ArrayList<>(byGroup.keySet());
        groups.sort(String::compareTo);
        for (String g : groups) {
            if (!groupFilter.isEmpty() && !groupFilter.equals(g)) {
                continue;
            }
            lines.add("[" + g + "]");
            List<AgentRole> members = byGroup.get(g);
            members.sort((a, b) -> a.name.compareTo(b.name));
            for (AgentRole r : members) {
                String desc = !r.title.isEmpty() ? r.title
                        : (!r.responsibilities.isEmpty() ? r.responsibilities : "team member");
                lines.add("  - " + r.name + " <" + mailService.emailFor(r) + "> — " + desc);
            }
        }
        if (!groupFilter.isEmpty() && !byGroup.containsKey(groupFilter)) {
            return "mail_address_book: Error: group not found: " + groupFilter + ". Available groups: "
                    + String.join(", ", groups);
        }
        return String.join("\n", lines);
    }
}
