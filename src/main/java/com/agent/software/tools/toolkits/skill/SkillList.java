package com.agent.software.tools.toolkits.skill;

import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * skill_list — list all available skills in the skill library (name + brief description).
 */
public class SkillList extends Tool {

    private final SkillManager manager;

    public SkillList(SkillManager manager) {
        super();
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "skill_list";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String handler(Map<String, Object> args) {
        List<Map<String, String>> avail = this.manager.listAvailable();
        if (avail.isEmpty()) {
            return "skill_list: no skills available (the skill library is empty; make sure data/skills/ exists).";
        }
        List<String> lines = new ArrayList<>();
        lines.add("skill_list: the skill library has " + avail.size() + " skills:");
        for (Map<String, String> a : avail) {
            lines.add("- " + a.get("name") + ": " + a.get("description"));
        }
        return String.join("\n", lines);
    }
}
