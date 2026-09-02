package com.agent.software.tools.toolkits.hr;

import com.agent.software.role.AgentRole;

import com.agent.software.role.RoleLoader;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * list_candidates - list all roles in the current role template pool (members who have onboarded),
 * including role_id, name, position.
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
        for (Map.Entry<String, Supplier<AgentRole>> e : RoleLoader.TEMPLATES.entrySet()) {
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
