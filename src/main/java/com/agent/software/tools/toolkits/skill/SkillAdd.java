package com.agent.software.tools.toolkits.skill;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * skill_add — add a skill for the current role. After adding, the skill can be invoked in tasks
 * (obtaining its complete usage instructions).
 */
public class SkillAdd extends Tool {

    private final AgentRole agentRole;
    private final SkillManager manager;

    public SkillAdd(AgentRole agentRole, SkillManager manager) {
        super();
        this.agentRole = agentRole;
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "skill_add";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("skill_name", "The skill name to add, e.g. pptx-generator.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object oname = args.get("skill_name");
        if (!(oname instanceof String)) {
            return oname == null
                    ? "skill_add: Error: needs skill_name"
                    : "skill_add: Error: skill_name is not a string";
        }
        String name = ((String) oname).strip();
        if (name.isEmpty()) {
            return "skill_add: Error: needs skill_name";
        }
        return this.manager.addSkill(agentRole, name);
    }
}
