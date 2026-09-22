package com.agent.software.tools.toolkits.skill;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** skill_add：把技能库里的技能装到自己身上。 */
public class SkillAdd extends Tool {

    private final Role role;
    private final SkillManager manager;

    public SkillAdd(Role role, SkillManager manager) {
        this.role = role;
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "skill_add";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "skill name to install");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Install a skill as a tool you can call.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        String name = args.get("name") == null ? "" : String.valueOf(args.get("name"));
        return manager.addSkill(role, name);
    }
}
