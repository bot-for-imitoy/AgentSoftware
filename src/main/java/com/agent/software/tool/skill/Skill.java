package com.agent.software.tool.skill;

import com.agent.software.kernel.Ids.SkillId;
import com.agent.software.kernel.Text;

import java.util.List;

/** 一条技能（SKILL.md 的解析结果）。 */
public record Skill(SkillId id, String name, String description, String body, List<String> files) {

    /**
     * 交给 LLM 的完整技能文本（frontmatter + 正文）。
     *
     * <p>格式对齐 master {@code SkillManager.readSkillContent} 的最终形态：先标题与描述，
     * 再空行分隔的正文；有附件文件时在末尾附上相对路径清单（模型据此用电脑工具取用）。
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(Text.orEmpty(name));
        if (!Text.isBlank(description)) {
            sb.append('\n').append(description.strip());
        }
        sb.append("\n\n").append(Text.orEmpty(body).strip());
        if (files != null && !files.isEmpty()) {
            sb.append("\n\n相关文件：");
            for (String file : files) {
                if (!Text.isBlank(file)) {
                    sb.append("\n- ").append(file.strip());
                }
            }
        }
        return sb.toString();
    }
}
