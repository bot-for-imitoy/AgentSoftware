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
 * mail_address_book — 查看公司通讯录: 所有成员按分组列出
 * (组名 → 姓名 <邮箱> — 职位). 发邮件前若不确定收件人, 先调用本工具.
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
        schema.put("group", "(Optional) Only show one group, e.g. '前端开发组'.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        RolePool pool = agentRole.pool();
        if (pool == null) {
            return "mail_address_book: Error: 当前角色未绑定角色池, 无法获取通讯录.";
        }
        Object ogroup = args.get("group");
        String groupFilter = ogroup instanceof String s ? s.strip() : "";
        Map<String, List<AgentRole>> byGroup = new LinkedHashMap<>();
        for (AgentRole r : pool.allRoles()) {
            String g = (r.group == null ? "" : r.group).strip();
            if (g.isEmpty()) {
                g = "未分组";
            }
            byGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(r);
        }
        List<String> lines = new ArrayList<>();
        lines.add("mail_address_book: 公司通讯录 (邮箱后缀 @" + mailService.config.suffix
                + ", 共 " + pool.allRoles().size() + " 人):");
        List<String> groups = new ArrayList<>(byGroup.keySet());
        groups.sort(String::compareTo);
        for (String g : groups) {
            if (!groupFilter.isEmpty() && !groupFilter.equals(g)) {
                continue;
            }
            lines.add("【" + g + "】");
            List<AgentRole> members = byGroup.get(g);
            members.sort((a, b) -> a.name.compareTo(b.name));
            for (AgentRole r : members) {
                String desc = !r.title.isEmpty() ? r.title
                        : (!r.responsibilities.isEmpty() ? r.responsibilities : "团队成员");
                lines.add("  - " + r.name + " <" + mailService.emailFor(r) + "> — " + desc);
            }
        }
        if (!groupFilter.isEmpty() && !byGroup.containsKey(groupFilter)) {
            return "mail_address_book: Error: 找不到分组「" + groupFilter + "」。可用分组: "
                    + String.join(", ", groups);
        }
        return String.join("\n", lines);
    }
}
