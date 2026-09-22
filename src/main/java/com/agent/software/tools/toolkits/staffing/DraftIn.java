package com.agent.software.tools.toolkits.staffing;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** draft_in：把一个员工抽调进当前任务大组（只有 COO 有这个工具）。 */
public class DraftIn extends Tool {

    private final Role role;

    public DraftIn(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "draft_in";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("role_id", "employee role_id to draft into the cohort");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Draft an employee into the current task cohort (creates their Role, computer and tools).";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "draft_in error: role not bound to a system";
        }
        String roleId = args.get("role_id") == null ? "" : String.valueOf(args.get("role_id"));
        try {
            Role drafted = role.getSystem().getStaffing().draftIn(roleId);
            return "draft_in: " + drafted.name + " (" + drafted.roleId + ", " + drafted.group
                    + ") joined the cohort";
        } catch (Exception e) {
            return "draft_in error: " + e.getMessage();
        }
    }
}
