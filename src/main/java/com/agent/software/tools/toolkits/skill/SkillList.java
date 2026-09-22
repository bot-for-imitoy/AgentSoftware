package com.agent.software.tools.toolkits.skill;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** skill_list：列出技能库里所有可用技能。 */
public class SkillList extends Tool {

    private final Role role;
    private final SkillManager manager;

    public SkillList(Role role, SkillManager manager) {
        this.role = role;
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
    public String getDescription() {
        return "List all skills available in the skill library.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        return "skill_list: " + Json.stringify(manager.listAvailable());
    }
}
