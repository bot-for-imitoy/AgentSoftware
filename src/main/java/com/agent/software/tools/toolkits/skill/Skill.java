package com.agent.software.tools.toolkits.skill;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/**
 * Skill toolkit — skill package management based on SKILL.md:
 * skill_search / skill_list / skill_add / skill_remove / skill_my_skills.
 *
 * Under the hood it reuses the globally shared {@link SkillManager} (scans the skill library SKILL.md).
 */
public class Skill extends Toolkit {

    private final Role role;
    private final SkillManager manager;

    public Skill(Role role, SkillManager manager) {
        this.role = role;
        this.manager = manager;
        addTool(new SkillSearch(manager));
        addTool(new SkillList(manager));
        addTool(new SkillAdd(role, manager));
        addTool(new SkillRemove(role, manager));
        addTool(new SkillMySkills(role, manager));
    }

    public Skill(Role role) {
        this(role, new SkillManager());
    }

    @Override
    public String getDescription(){
        return "Skill management: search/list/add/remove SKILL.md skill tools and view added skills";
    }

}
