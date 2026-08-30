package com.maf.scheduler.tools.toolkits.skill;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.tools.SkillToolkit;
import com.maf.scheduler.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * skill_add — 为当前角色添加一个技能. 添加后即可在任务中调用该技能
 * (获得其完整使用指引).
 */
public class SkillAdd extends Tool {

    private final AgentRole agentRole;
    private final SkillToolkit.SkillManager manager;

    public SkillAdd(AgentRole agentRole, SkillToolkit.SkillManager manager) {
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
