package com.agent.software.tools.toolkits.staffing;

import com.agent.software.role.Employee;
import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** list_employees：查看公司员工名单（含是否在组）。 */
public class ListEmployees extends Tool {

    private final Role role;

    public ListEmployees(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "list_employees";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("group", "optional group filter");
        return schema;
    }

    @Override
    public String getDescription() {
        return "List company employees (roster), marking who is currently in the cohort.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "list_employees error: role not bound to a system";
        }
        String group = args.get("group") == null ? "" : String.valueOf(args.get("group"));
        StringBuilder sb = new StringBuilder("list_employees:\n");
        for (Employee e : role.getSystem().getRoster().all()) {
            if (!group.isBlank() && !group.equals(e.group)) {
                continue;
            }
            sb.append("  - ").append(e.roleId).append(" | ").append(e.name).append(" | ").append(e.group)
                    .append(" | ").append(e.membership).append('\n');
        }
        return sb.toString();
    }
}
