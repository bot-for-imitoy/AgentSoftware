package com.agent.software.tool.skill;

import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.Toolkit;

import java.util.List;

/**
 * 技能工具包（id {@code "skill"}），暴露工具：skill_list / skill_search / skill_add / skill_remove / skill_my_skills。
 */
public final class SkillToolkit implements Toolkit {

    private final SkillLibrary skills;

    public SkillToolkit(SkillLibrary skills) {
        this.skills = skills;
    }

    @Override
    public String id() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Tool> instantiate() {
        throw new UnsupportedOperationException("skeleton");
    }
}
