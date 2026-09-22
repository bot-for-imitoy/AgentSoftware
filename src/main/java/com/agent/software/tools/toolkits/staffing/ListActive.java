package com.agent.software.tools.toolkits.staffing;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** list_active：列出当前大组。 */
public class ListActive extends Tool {

    private final Role role;

    public ListActive(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "list_active";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String getDescription() {
        return "List the members of the current task cohort.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "list_active error: role not bound to a system";
        }
        StringBuilder sb = new StringBuilder("list_active (" + role.getSystem().getRolePool().size() + "):\n");
        for (Role r : role.getSystem().getRolePool().all()) {
            sb.append("  - ").append(r.roleId).append(" | ").append(r.name).append(" | ").append(r.group)
                    .append(" | ").append(r.getState()).append('\n');
        }
        return sb.toString();
    }
}
