package com.agent.software.tools.toolkits.skill;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * skill_remove — 从当前角色移除一个已添加的技能.
 */
public class SkillRemove extends Tool {

    private final AgentRole agentRole;
    private final SkillManager manager;

    public SkillRemove(AgentRole agentRole, SkillManager manager) {
        super();
        this.agentRole = agentRole;
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "skill_remove";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("skill_name", "The skill name to remove.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object oname = args.get("skill_name");
        if (!(oname instanceof String)) {
            return oname == null
                    ? "skill_remove: Error: needs skill_name"
                    : "skill_remove: Error: skill_name is not a string";
        }
        String name = ((String) oname).strip();
        if (name.isEmpty()) {
            return "skill_remove: Error: needs skill_name";
        }
        return this.manager.removeSkill(agentRole, name);
    }
}
