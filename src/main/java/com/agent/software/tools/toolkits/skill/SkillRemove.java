package com.agent.software.tools.toolkits.skill;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** skill_remove：取消登记一个技能。 */
public class SkillRemove extends Tool {

    private final Role role;
    private final SkillManager manager;

    public SkillRemove(Role role, SkillManager manager) {
        this.role = role;
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "skill_remove";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "skill name to remove");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Unregister a skill you added.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        String name = args.get("name") == null ? "" : String.valueOf(args.get("name"));
        return manager.removeSkill(role, name);
    }
}
