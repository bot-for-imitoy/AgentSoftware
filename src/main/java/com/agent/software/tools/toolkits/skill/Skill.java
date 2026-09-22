package com.agent.software.tools.toolkits.skill;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/** 技能工具包：search / add / remove / list / my_skills。 */
public class Skill extends Toolkit {

    private final SkillManager manager;

    public Skill(Role role, SkillManager manager) {
        this.manager = manager == null ? new SkillManager() : manager;
        addTool(new SkillSearch(role, this.manager));
        addTool(new SkillAdd(role, this.manager));
        addTool(new SkillRemove(role, this.manager));
        addTool(new SkillList(role, this.manager));
        addTool(new SkillMySkills(role, this.manager));
    }

    @Override
    public String getDescription() {
        return "Skills: search/add/remove/list skills and see my skills";
    }
}
