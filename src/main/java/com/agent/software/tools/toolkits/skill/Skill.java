package com.agent.software.tools.toolkits.skill;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Toolkit;

/**
 * 技能工具类 (Skill Toolkit) — 基于 SKILL.md 的技能包管理:
 * skill_search / skill_list / skill_add / skill_remove / skill_my_skills.
 *
 * 底层复用全局共享的 {@link SkillManager} (扫描技能库 SKILL.md).
 */
public class Skill extends Toolkit {

    private final AgentRole agentRole;
    private final SkillManager manager;

    public Skill(AgentRole agentRole, SkillManager manager) {
        this.agentRole = agentRole;
        this.manager = manager;
        addTool(new SkillSearch(manager));
        addTool(new SkillList(manager));
        addTool(new SkillAdd(agentRole, manager));
        addTool(new SkillRemove(agentRole, manager));
        addTool(new SkillMySkills(agentRole, manager));
    }

    public Skill(AgentRole agentRole) {
        this(agentRole, new SkillManager());
    }

    @Override
    public String getDescription(){
        return "Skill management: search/list/add/remove SKILL.md skill tools and view added skills";
    }

}
