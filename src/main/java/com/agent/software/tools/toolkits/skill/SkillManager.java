package com.agent.software.tools.toolkits.skill;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 技能库：每个技能是 skills 目录下的一个子目录（含 SKILL.md）。
 *
 * <p>角色"添加技能"= 把该技能包装成一个 Tool 挂到角色上（{@code Role.addTool}）。
 */
public class SkillManager {

    private static final Logger logger = LoggerFactory.getLogger(SkillManager.class);

    public static final class SkillInfo {
        public final String name;
        public final String description;
        public final Path path;

        public SkillInfo(String name, String description, Path path) {
            this.name = name;
            this.description = description == null ? "" : description;
            this.path = path;
        }

        public String toolName() {
            return "skill_" + name.toLowerCase().replaceAll("[^a-z0-9_]+", "_");
        }

        public String readSkillMd() {
            try {
                Path md = path.resolve("SKILL.md");
                return Files.exists(md) ? Files.readString(md, StandardCharsets.UTF_8) : "";
            } catch (IOException e) {
                return "";
            }
        }

        public List<String> listRelatedFiles() {
            List<String> out = new ArrayList<>();
            try (Stream<Path> stream = Files.list(path)) {
                stream.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .forEach(out::add);
            } catch (IOException ignored) {
            }
            return out;
        }
    }

    private final Path skillsDir;
    private final Map<String, SkillInfo> skills = new LinkedHashMap<>();
    private final Map<String, Set<String>> roleSkills = new ConcurrentHashMap<>();

    public SkillManager(String skillsDir) {
        this.skillsDir = skillsDir == null ? Paths.get("data", "skills") : Paths.get(skillsDir);
    }

    public SkillManager() {
        this(System.getenv().getOrDefault("AGENTSOFTWARE_SKILLS_DIR", "data/skills"));
    }

    /** 扫描 skills 目录。 */
    public Map<String, SkillInfo> ensureLoaded() {
        skills.clear();
        if (!Files.isDirectory(skillsDir)) {
            return skills;
        }
        try (Stream<Path> stream = Files.list(skillsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString();
                String description = firstLine(dir.resolve("SKILL.md"));
                skills.put(name, new SkillInfo(name, description, dir));
            }
        } catch (IOException e) {
            logger.warn("cannot scan skills dir {}", skillsDir, e);
        }
        return skills;
    }

    public List<Map<String, String>> listAvailable() {
        ensureLoaded();
        List<Map<String, String>> out = new ArrayList<>();
        for (SkillInfo info : skills.values()) {
            out.add(summary(info));
        }
        return out;
    }

    public List<Map<String, String>> searchSkills(String keyword) {
        ensureLoaded();
        String q = keyword == null ? "" : keyword.toLowerCase();
        List<Map<String, String>> out = new ArrayList<>();
        for (SkillInfo info : skills.values()) {
            if (q.isBlank() || info.name.toLowerCase().contains(q) || info.description.toLowerCase().contains(q)) {
                out.add(summary(info));
            }
        }
        return out;
    }

    public String addSkill(Role role, String skillName) {
        if (role == null) {
            return "skill_add error: no role";
        }
        ensureLoaded();
        SkillInfo info = skills.get(skillName);
        if (info == null) {
            return "skill_add: no such skill '" + skillName + "'";
        }
        roleSkills.computeIfAbsent(role.roleId, k -> new LinkedHashSet<>()).add(skillName);
        role.addTool(skillTool(info));
        return "skill_add: '" + skillName + "' installed for " + role.roleId;
    }

    public String removeSkill(Role role, String skillName) {
        if (role == null) {
            return "skill_remove error: no role";
        }
        Set<String> mine = roleSkills.get(role.roleId);
        boolean removed = mine != null && mine.remove(skillName);
        return removed
                ? "skill_remove: '" + skillName + "' unregistered (already-added tools are not removed at runtime)"
                : "skill_remove: '" + skillName + "' was not added";
    }

    public List<Map<String, String>> listRoleSkills(Role role) {
        List<Map<String, String>> out = new ArrayList<>();
        if (role == null) {
            return out;
        }
        ensureLoaded();
        for (String name : roleSkills.getOrDefault(role.roleId, Set.of())) {
            SkillInfo info = skills.get(name);
            if (info != null) {
                out.add(summary(info));
            }
        }
        return out;
    }

    private Tool skillTool(SkillInfo info) {
        return new Tool() {
            @Override
            public String getToolName() {
                return info.toolName();
            }

            @Override
            public Map<String, Object> getSchema() {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("args", "optional arguments for the skill");
                return schema;
            }

            @Override
            public String getDescription() {
                return "Skill '" + info.name + "': " + info.description;
            }

            @Override
            public String handler(Map<String, Object> args) {
                StringBuilder sb = new StringBuilder(info.readSkillMd());
                List<String> files = info.listRelatedFiles();
                if (!files.isEmpty()) {
                    sb.append("\n\nRelated files: ").append(String.join(", ", files));
                }
                return sb.toString();
            }
        };
    }

    private static Map<String, String> summary(SkillInfo info) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", info.name);
        m.put("description", info.description);
        m.put("tool", info.toolName());
        return m;
    }

    private static String firstLine(Path skillMd) {
        try {
            if (!Files.exists(skillMd)) {
                return "";
            }
            for (String line : Files.readString(skillMd, StandardCharsets.UTF_8).split("\n")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    return t;
                }
            }
        } catch (IOException ignored) {
        }
        return "";
    }
}
