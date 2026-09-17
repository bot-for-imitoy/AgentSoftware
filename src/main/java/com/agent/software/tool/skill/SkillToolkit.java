package com.agent.software.tool.skill;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.SkillId;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolkit;

import java.util.List;
import java.util.Optional;

/**
 * 技能工具包（id {@code "skill"}），暴露工具：skill_list / skill_search / skill_add / skill_remove / skill_my_skills。
 *
 * <p>工具只做参数校验与展示，技能发现与授权关系都委托给 {@link SkillLibrary}；
 * {@code skill_add} 授权给调用者自己（owner 就是 invoke 的 agent 参数）。
 */
public final class SkillToolkit implements Toolkit {

    private final SkillLibrary skills;

    public SkillToolkit(SkillLibrary skills) {
        this.skills = skills;
    }

    @Override
    public String id() {
        return "skill";
    }

    @Override
    public List<Tool> instantiate() {
        return List.of(new SkillList(), new SkillSearch(), new SkillAdd(), new SkillRemove(), new SkillMySkills());
    }

    // ── skill_list ─────────────────────────────────────────────────

    private final class SkillList implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("skill_list", "列出技能库里全部可用技能（名称 + 简述）。", JsonSchema.object());
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            List<Skill> all = skills.available();
            if (all.isEmpty()) {
                return ToolResult.ok("skill_list: 技能库为空（请确认数据目录下的 skills/ 已放置技能）。");
            }
            StringBuilder sb = new StringBuilder("skill_list: 技能库共有 " + all.size() + " 个技能：");
            for (Skill skill : all) {
                appendBrief(sb, skill);
            }
            return ToolResult.ok(sb.toString());
        }
    }

    // ── skill_search ───────────────────────────────────────────────

    private final class SkillSearch implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("skill_search", "按关键词搜索技能（匹配名称、描述与正文，不区分大小写）。",
                    JsonSchema.object()
                            .string("keyword", "搜索关键词，例如 ppt / video / pdf / 写作")
                            .required("keyword"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String keyword = arg(arguments, "keyword");
            if (Text.isBlank(keyword)) {
                return ToolResult.error("skill_search: 缺少搜索关键词（keyword）");
            }
            List<Skill> hits = skills.search(keyword);
            if (hits.isEmpty()) {
                return ToolResult.ok("skill_search: 没有匹配 '" + keyword + "' 的技能，可用 skill_list 查看全部。");
            }
            StringBuilder sb = new StringBuilder("skill_search: 找到 " + hits.size() + " 个匹配 '" + keyword + "' 的技能：");
            for (Skill skill : hits) {
                appendBrief(sb, skill);
            }
            return ToolResult.ok(sb.toString());
        }
    }

    // ── skill_add ──────────────────────────────────────────────────

    private final class SkillAdd implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("skill_add", "把一个技能授权给自己（之后可在任务中使用它的完整说明）。",
                    JsonSchema.object()
                            .string("skill_id", "技能 id（即技能目录名，来自 skill_list / skill_search）")
                            .required("skill_id"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String rawId = arg(arguments, "skill_id");
            if (Text.isBlank(rawId)) {
                return ToolResult.error("skill_add: 缺少技能 id（skill_id）");
            }
            Optional<Skill> found = find(rawId);
            if (found.isEmpty()) {
                return ToolResult.error("skill_add: 技能库里没有名为 '" + rawId
                        + "' 的技能，请先用 skill_list / skill_search 确认。");
            }
            try {
                if (isOwned(agent, rawId)) {
                    return ToolResult.ok("skill_add: 你已经拥有技能 '" + rawId + "'，无需重复添加。");
                }
                skills.grant(agent, found.get().id());
                return ToolResult.ok("skill_add: 已添加技能 '" + rawId + "'：" + found.get().name() + "。");
            } catch (RuntimeException e) {
                return ToolResult.error("skill_add: 添加失败: " + e.getMessage());
            }
        }
    }

    // ── skill_remove ───────────────────────────────────────────────

    private final class SkillRemove implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("skill_remove", "从自己身上移除一个已添加的技能。",
                    JsonSchema.object()
                            .string("skill_id", "要移除的技能 id")
                            .required("skill_id"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String rawId = arg(arguments, "skill_id");
            if (Text.isBlank(rawId)) {
                return ToolResult.error("skill_remove: 缺少技能 id（skill_id）");
            }
            try {
                if (!isOwned(agent, rawId)) {
                    return ToolResult.error("skill_remove: 你还没有技能 '" + rawId + "'，无需移除。");
                }
                skills.revoke(agent, new SkillId(rawId));
                return ToolResult.ok("skill_remove: 已移除技能 '" + rawId + "'。");
            } catch (RuntimeException e) {
                return ToolResult.error("skill_remove: 移除失败: " + e.getMessage());
            }
        }
    }

    // ── skill_my_skills ────────────────────────────────────────────

    private final class SkillMySkills implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("skill_my_skills", "查看自己已经添加的技能。", JsonSchema.object());
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            List<Skill> mine = skills.ownedBy(agent);
            if (mine.isEmpty()) {
                return ToolResult.ok("skill_my_skills: 你还没有添加任何技能，"
                        + "可用 skill_search / skill_list 查找后用 skill_add 添加。");
            }
            StringBuilder sb = new StringBuilder("skill_my_skills: 你已添加 " + mine.size() + " 个技能：");
            for (Skill skill : mine) {
                appendBrief(sb, skill);
            }
            return ToolResult.ok(sb.toString());
        }
    }

    // ── 内部 ───────────────────────────────────────────────────────

    private static void appendBrief(StringBuilder sb, Skill skill) {
        sb.append("\n- ").append(skill.id().value());
        String name = Text.orEmpty(skill.name());
        if (!Text.isBlank(name) && !name.equals(skill.id().value())) {
            sb.append("（").append(name).append("）");
        }
        sb.append(": ").append(Text.truncate(Text.squashWhitespace(skill.description()), 120));
    }

    private Optional<Skill> find(String id) {
        for (Skill skill : skills.available()) {
            if (skill.id().value().equals(id)) {
                return Optional.of(skill);
            }
        }
        return Optional.empty();
    }

    private boolean isOwned(RoleId agent, String id) {
        for (Skill skill : skills.ownedBy(agent)) {
            if (skill.id().value().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static String arg(Payload arguments, String name) {
        return Text.orEmpty(arguments.stringOr(name, "")).strip();
    }
}
