package com.agent.software.tools.toolkits.talk;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * list_roles：列出当前可以沟通的人。
 *
 * <p>按 F2：只显示被 COO 抽调进大组的成员；非管理组还会再受"同部门"限制，
 * 管理组豁免部门限制。
 */
public class ListRoles extends Tool {

    private final Role role;

    public ListRoles(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "list_roles";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String getDescription() {
        return "List cohort members you can talk to.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "list_roles error: role not bound to a system";
        }
        StringBuilder sb = new StringBuilder("list_roles (cohort):\n");
        int shown = 0;
        for (Role r : role.getSystem().getRolePool().all()) {
            if (r == role) {
                continue;
            }
            boolean talkable = role.canTalkTo(r);
            sb.append("  - ").append(r.name).append(" (").append(r.roleId).append(", ").append(r.group)
                    .append(") ").append(talkable ? "[talkable]" : "[not talkable: different department]")
                    .append('\n');
            shown++;
        }
        if (shown == 0) {
            sb.append("  (nobody else in the cohort yet)");
        }
        return sb.toString();
    }
}
