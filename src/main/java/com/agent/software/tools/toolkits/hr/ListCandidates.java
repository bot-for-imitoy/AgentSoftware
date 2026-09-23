package com.agent.software.tools.toolkits.hr;

import com.agent.software.role.Employee;
import com.agent.software.role.Role;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** list_candidates：列出公司员工名单（含是否已在当前大组）。 */
public class ListCandidates extends Tool {

    private final Role role;

    public ListCandidates(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "list_candidates";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("query", "optional keyword to filter role_id / name / title / group");
        return schema;
    }

    @Override
    public String getDescription() {
        return "List company employees (role_id, name, title, group, in-cohort or not).";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "list_candidates error: role not bound to a system";
        }
        String query = args.get("query") == null ? "" : String.valueOf(args.get("query")).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Employee e : role.getSystem().getRoster().all()) {
            String title = e.templateString("title", "");
            if (!query.isBlank()
                    && !e.roleId.toLowerCase(Locale.ROOT).contains(query)
                    && !e.name.toLowerCase(Locale.ROOT).contains(query)
                    && !title.toLowerCase(Locale.ROOT).contains(query)
                    && !e.group.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role_id", e.roleId);
            m.put("name", e.name);
            m.put("title", title);
            m.put("group", e.group);
            m.put("membership", e.membership.name());
            m.put("skills", e.templateList("skills"));
            out.add(m);
        }
        return "list_candidates (" + out.size() + "): " + Json.stringifyPretty(out);
    }
}
