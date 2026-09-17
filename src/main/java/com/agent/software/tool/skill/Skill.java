package com.agent.software.tool.skill;

import com.agent.software.kernel.Ids.SkillId;

import java.util.List;

/** 一条技能（SKILL.md 的解析结果）。 */
public record Skill(SkillId id, String name, String description, String body, List<String> files) {

    /** 交给 LLM 的完整技能文本（frontmatter + 正文）。 */
    public String render() {
        throw new UnsupportedOperationException("skeleton");
    }
}
