package com.agent.software.tools.toolkits.hr;

import com.agent.software.role.AgentRole;

import com.agent.software.role.RoleTemplates;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * list_candidates — 列出当前角色模板池中的所有角色 (已入职的成员),
 * 包含 role_id, 姓名, 职位.
 */
public class ListCandidates extends Tool {

    public ListCandidates() {
        super();
    }

    @Override
    public String getToolName() {
        return "list_candidates";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String handler(Map<String, Object> args) {
        List<Map<String, Object>> roles = new ArrayList<>();
        for (Map.Entry<String, Supplier<AgentRole>> e : RoleTemplates.TEMPLATES.entrySet()) {
            AgentRole r = e.getValue().get();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role_id", r.roleId);
            m.put("name", r.name);
            m.put("title", r.title);
            m.put("skills_count", r.skills.size());
            roles.add(m);
        }
        return "list_candidates: " + Json.stringifyPretty(roles);
    }
}
