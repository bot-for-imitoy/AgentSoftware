package com.agent.software.tools.toolkits.skill;

import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * skill_search — search skills in the skill library (by name or description keyword).
 * First search to find the right skill, then use skill_add to add it to yourself.
 */
public class SkillSearch extends Tool {

    private final SkillManager manager;

    public SkillSearch(SkillManager manager) {
        super();
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "skill_search";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("keyword", "Search keyword, e.g. ppt/video/pdf/writing.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object okeyword = args.get("keyword");
        if (!(okeyword instanceof String)) {
            return okeyword == null
                    ? "skill_search: Error: needs keyword"
                    : "skill_search: Error: keyword is not a string";
        }
        String kw = ((String) okeyword).strip();
        if (kw.isEmpty()) {
            return "skill_search: Error: needs keyword";
        }
        List<Map<String, String>> hits = this.manager.searchSkills(kw);
        if (hits.isEmpty()) {
            return "skill_search: no skill matching '" + kw + "'. Use skill_list to see all.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("skill_search: found " + hits.size() + " skills matching '" + kw + "':");
        for (Map<String, String> h : hits) {
            lines.add("- " + h.get("name") + ": " + h.get("description"));
        }
        return String.join("\n", lines);
    }
}
