package com.agent.software.tools.toolkits.skill;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * skill_my_skills — 查看当前角色已添加的技能列表.
 */
public class SkillMySkills extends Tool {

    private final AgentRole agentRole;
    private final SkillManager manager;

    public SkillMySkills(AgentRole agentRole, SkillManager manager) {
        super();
        this.agentRole = agentRole;
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
    public String handler(Map<String, Object> args) {
        List<Map<String, String>> mine = this.manager.listRoleSkills(agentRole);
        if (mine.isEmpty()) {
            return "skill_my_skills: 你还没有添加任何技能. 可用 skill_search / skill_list 寻找, 用 skill_add 添加.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("skill_my_skills: 你已添加 " + mine.size() + " 个技能:");
        for (Map<String, String> m : mine) {
            lines.add("- " + m.get("name") + ": " + m.get("description"));
        }
        return String.join("\n", lines);
    }
}
