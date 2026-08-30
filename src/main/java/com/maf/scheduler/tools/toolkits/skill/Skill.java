package com.maf.scheduler.tools.toolkits.skill;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.tools.Toolkit;

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
        return "技能管理: 搜索/列出/添加/移除 SKILL.md 技能工具, 查看已添加的技能";
    }

}
