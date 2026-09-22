package com.agent.software.tools.toolkits.skill;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** skill_search：按关键词搜索技能库。 */
public class SkillSearch extends Tool {

    private final Role role;
    private final SkillManager manager;

    public SkillSearch(Role role, SkillManager manager) {
        this.role = role;
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "skill_search";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("query", "keyword to search skills");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Search the skill library by keyword.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        String query = args.get("query") == null ? "" : String.valueOf(args.get("query"));
        return "skill_search: " + Json.stringify(manager.searchSkills(query));
    }
}
