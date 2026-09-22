package com.agent.software.tools.toolkits.skill;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** skill_my_skills：列出我已经装上的技能。 */
public class SkillMySkills extends Tool {

    private final Role role;
    private final SkillManager manager;

    public SkillMySkills(Role role, SkillManager manager) {
        this.role = role;
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "skill_my_skills";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String getDescription() {
        return "List the skills you have installed.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        return "skill_my_skills: " + Json.stringify(manager.listRoleSkills(role));
    }
}
