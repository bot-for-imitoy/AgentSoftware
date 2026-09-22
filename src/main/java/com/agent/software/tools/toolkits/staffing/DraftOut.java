package com.agent.software.tools.toolkits.staffing;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** draft_out：把员工移出大组（电脑关机、数据保留）。 */
public class DraftOut extends Tool {

    private final Role role;

    public DraftOut(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "draft_out";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("role_id", "cohort member role_id to remove");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Remove a member from the cohort. Their computer is powered off and their data is kept.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "draft_out error: role not bound to a system";
        }
        String roleId = args.get("role_id") == null ? "" : String.valueOf(args.get("role_id"));
        if (role.getSystem().getRolePool().find(roleId) == null) {
            return "draft_out: '" + roleId + "' is not in the cohort";
        }
        role.getSystem().getStaffing().draftOut(roleId);
        return "draft_out: " + roleId + " left the cohort (data kept)";
    }
}
