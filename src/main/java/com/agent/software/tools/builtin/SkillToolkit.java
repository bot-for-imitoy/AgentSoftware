package com.agent.software.tools.builtin;

import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.SkillRepository;
import com.agent.software.ports.ToolResult;
import com.agent.software.ports.ToolSpec;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.ToolService;
import com.agent.software.tools.spi.Toolkit;
import com.agent.software.tools.spi.Tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code skill} toolkit: browse the shared SKILL.md library and install a
 * skill as a callable tool for the calling role.
 *
 * <p>Installing registers a no-argument tool whose result is the full skill
 * instructions; removing unregisters it.
 */
public final class SkillToolkit {

    private SkillToolkit() {
    }

    public static Toolkit create(ToolService tools, SkillRepository skills) {
        Map<RoleId, Set<String>> added = new ConcurrentHashMap<>();

        Tool list = Tools.of("skill_list", "List the skills available in the company skill library",
                JsonSchema.object(),
                (role, call) -> render("skill_list", skills.available()));

        Tool search = Tools.of("skill_search", "Search the skill library by keyword",
                JsonSchema.builder()
                        .required("keyword", JsonSchema.Property.string("Search keyword, e.g. ppt/video/pdf."))
                        .build(),
                (role, call) -> {
                    String keyword = Tools.argStripped(call, "keyword");
                    if (keyword.isEmpty()) {
                        return ToolResult.error("skill_search: Error: needs keyword");
                    }
                    return render("skill_search", skills.search(keyword));
                });

        Tool add = Tools.of("skill_add", "Install a skill from the library as a callable tool",
                JsonSchema.builder()
                        .required("skill_name", JsonSchema.Property.string("The skill name to add."))
                        .build(),
                (role, call) -> {
                    String name = Tools.argStripped(call, "skill_name");
                    if (name.isEmpty()) {
                        return ToolResult.error("skill_add: Error: needs skill_name");
                    }
                    Optional<SkillRepository.SkillInfo> info = find(skills, name);
                    if (info.isEmpty()) {
                        return ToolResult.error("skill_add: Error: no skill named '" + name
                                + "' exists in the library. Use skill_search/skill_list first.");
                    }
                    if (tools.hasTool(role, info.get().toolName())) {
                        return ToolResult.success("skill_add: skill '" + name + "' is already installed.");
                    }
                    tools.addTool(role,
                            new ToolSpec(info.get().toolName(),
                                    info.get().description().isEmpty() ? "Skill: " + name : info.get().description(),
                                    JsonSchema.object()),
                            (r, c) -> skills.readSkill(name)
                                    .map(ToolResult::success)
                                    .orElseGet(() -> ToolResult.error("skill '" + name + "' is no longer available")));
                    added.computeIfAbsent(role, k -> ConcurrentHashMap.newKeySet()).add(name);
                    return ToolResult.success("skill_add: skill '" + name + "' installed as tool '"
                            + info.get().toolName() + "'.");
                });

        Tool remove = Tools.of("skill_remove", "Uninstall a skill you previously added",
                JsonSchema.builder()
                        .required("skill_name", JsonSchema.Property.string("The skill name to remove."))
                        .build(),
                (role, call) -> {
                    String name = Tools.argStripped(call, "skill_name");
                    if (name.isEmpty()) {
                        return ToolResult.error("skill_remove: Error: needs skill_name");
                    }
                    Set<String> mine = added.get(role);
                    boolean tracked = mine != null && mine.remove(name);
                    Optional<SkillRepository.SkillInfo> info = find(skills, name);
                    boolean existed = info.isPresent() && tools.removeTool(role, info.get().toolName());
                    if (!tracked && !existed) {
                        return ToolResult.error("skill_remove: Error: skill '" + name + "' is not installed.");
                    }
                    return ToolResult.success("skill_remove: skill '" + name + "' removed.");
                });

        Tool mine = Tools.of("skill_my_skills", "List the skills you have installed",
                JsonSchema.object(),
                (role, call) -> {
                    Set<String> installed = added.get(role);
                    if (installed == null || installed.isEmpty()) {
                        return ToolResult.success("skill_my_skills: you have not installed any skills yet. "
                                + "Use skill_search/skill_list to find them and skill_add to install.");
                    }
                    List<String> names = new ArrayList<>(installed);
                    names.sort(String::compareTo);
                    StringBuilder sb = new StringBuilder("skill_my_skills: " + names.size() + " installed:");
                    for (String name : names) {
                        sb.append("\n- ").append(name);
                    }
                    return ToolResult.success(sb.toString());
                });

        return new Toolkit("skill", "Skill management: search/list/add/remove SKILL.md skills",
                List.of(list, search, add, remove, mine));
    }

    private static Optional<SkillRepository.SkillInfo> find(SkillRepository skills, String name) {
        return skills.available().stream()
                .filter(info -> info.name().equals(name))
                .findFirst();
    }

    private static ToolResult render(String prefix, List<SkillRepository.SkillInfo> skills) {
        if (skills.isEmpty()) {
            return ToolResult.success(prefix + ": no skills available.");
        }
        StringBuilder sb = new StringBuilder(prefix + ": " + skills.size() + " skill(s):");
        for (SkillRepository.SkillInfo info : skills) {
            sb.append("\n- ").append(info.name());
            if (!info.description().isEmpty()) {
                sb.append(": ").append(info.description());
            }
        }
        return ToolResult.success(sb.toString());
    }
}
