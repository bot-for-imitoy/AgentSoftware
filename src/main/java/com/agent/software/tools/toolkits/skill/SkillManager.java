package com.agent.software.tools.toolkits.skill;

import com.agent.software.role.AgentRole;
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
import java.util.regex.Pattern;

/**
 * Skill library manager — scans SKILL.md and registers skills as role tools on demand
 * (formerly SkillToolkit.SkillManager, moved into the template-style package when the old tool classes were removed).
 *
 * Each skill = one directory containing SKILL.md (frontmatter: name/description +
 * usage instructions) plus an optional scripts/.
 */
public final class SkillManager {

    private static final Logger logger = LoggerFactory.getLogger(SkillManager.class);

    private static final Pattern TOOL_NAME_RE = Pattern.compile("[^a-z0-9_]+");

    /** Metadata of a skill package. */
    public static final class SkillInfo {
        public final String name;
        public final String description;
        public final Path path;

        public SkillInfo(String name, String description, Path path) {
            this.name = name;
            this.description = description;
            this.path = path;
        }

        /** Convert to a valid tool name (lowercase with underscores, spaces/hyphens → underscores). */
        public String toolName() {
            String n = TOOL_NAME_RE.matcher(name.toLowerCase()).replaceAll("_");
            n = n.replaceAll("^_+|_+$", "");
            return n.isEmpty() ? "skill" : n;
        }

        /** Read the full SKILL.md text (returns empty string if it does not exist). */
        public String readSkillMd() {
            Path p = path.resolve("SKILL.md");
            if (!Files.exists(p)) {
                return "";
            }
            try {
                return Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }

        /** List related files under the skill directory (scripts/references/assets), sorted by relative path. */
        public List<String> listRelatedFiles() {
            List<String> files = new ArrayList<>();
            for (String sub : new String[]{"scripts", "references", "assets"}) {
                Path d = path.resolve(sub);
                if (!Files.isDirectory(d)) {
                    continue;
                }
                try (var stream = Files.walk(d)) {
                    stream.filter(Files::isRegularFile).sorted()
                            .forEach(p -> files.add(path.relativize(p).toString()));
                } catch (IOException ignored) {
                }
            }
            return files;
        }
    }

    public final Path skillsDir;
    private final Map<String, SkillInfo> skills = new LinkedHashMap<>();
    private final Map<String, Set<String>> roleSkills = new LinkedHashMap<>();
    private boolean loaded = false;

    public SkillManager(String skillsDir) {
        this.skillsDir = skillsDir != null ? Paths.get(skillsDir)
                : Paths.get("data", "skills");
    }

    public SkillManager() {
        this(null);
    }

    /** Scan all SKILL.md files in the skill library and parse the frontmatter. Idempotent. */
    public Map<String, SkillInfo> ensureLoaded() {
        if (loaded) {
            return skills;
        }
        if (!Files.isDirectory(skillsDir)) {
            logger.warn("Skill library directory does not exist: {} (clone anbeime/skill and copy skills/ over)", skillsDir);
            loaded = true;
            return skills;
        }
        try (var stream = Files.walk(skillsDir)) {
            List<Path> mds = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                    .sorted().forEach(mds::add);
            for (Path md : mds) {
                String text;
                try {
                    text = Files.readString(md, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                String[] fm = parseFrontmatter(text);
                String name = fm[0];
                if (name == null || name.isEmpty()) {
                    name = md.getParent().getFileName().toString();
                }
                // Name conflicts: keep the first, append a sequence number to later ones
                String base = name;
                int idx = 2;
                while (skills.containsKey(name)) {
                    name = base + "-" + idx;
                    idx++;
                }
                skills.put(name, new SkillInfo(name, fm[1], md.getParent()));
            }
        } catch (IOException e) {
            logger.warn("Failed to scan the skill library: {}", e.getMessage());
        }
        loaded = true;
        logger.info("SkillManager: loaded {} skills (from {})", skills.size(), skillsDir);
        return skills;
    }

    /** List all skills in the skill library. */
    public List<Map<String, String>> listAvailable() {
        ensureLoaded();
        List<Map<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, SkillInfo> e : skills.entrySet()) {
            SkillInfo s = e.getValue();
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", s.name);
            m.put("description", truncate(s.description, 120));
            m.put("path", s.path.toString());
            out.add(m);
        }
        return out;
    }

    /** Search skills by keyword (matches name or description). */
    public List<Map<String, String>> searchSkills(String keyword) {
        ensureLoaded();
        String kw = (keyword == null ? "" : keyword).strip().toLowerCase();
        if (kw.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, String>> hits = new ArrayList<>();
        for (Map.Entry<String, SkillInfo> e : skills.entrySet()) {
            SkillInfo s = e.getValue();
            String haystack = (s.name + " " + (s.description == null ? "" : s.description)).toLowerCase();
            if (haystack.contains(kw)) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", s.name);
                m.put("description", truncate(s.description, 120));
                m.put("path", s.path.toString());
                hits.add(m);
            }
        }
        return hits;
    }

    /** Install a skill tool for a role. */
    public String addSkill(AgentRole role, String skillName) {
        ensureLoaded();
        SkillInfo info = skills.get(skillName);
        if (info == null) {
            return "Error: no skill named '" + skillName + "' exists in the skill library. Use skill_search / skill_list to see all skills.";
        }
        String roleId = role.roleId;
        Set<String> mine = roleSkills.computeIfAbsent(roleId, k -> new LinkedHashSet<>());
        if (mine.contains(skillName)) {
            return "Skill '" + skillName + "' is already added to " + roleId + ", no need to add it again.";
        }
        SkillInfo infoRef = info;
        role.addSingleTool(info.toolName(),
                truncate(info.description == null || info.description.isEmpty()
                        ? "Skill: " + info.name : info.description, 300),
                emptySchema(),
                args -> readSkillContent(infoRef),
                "skill:" + info.name);
        mine.add(skillName);
        logger.info("[{}] skill tool added: {} (from {})", roleId, skillName, info.path);
        return "Success: skill '" + skillName + "' installed to " + roleId + " (" + truncate(info.description, 60) + "...)";
    }

    /** Remove a skill tool from a role. */
    public String removeSkill(AgentRole role, String skillName) {
        String roleId = role.roleId;
        Set<String> mine = roleSkills.getOrDefault(roleId, new LinkedHashSet<>());
        if (!mine.contains(skillName)) {
            return "Skill '" + skillName + "' has not been added to " + roleId + ", nothing to remove.";
        }
        SkillInfo info = skills.get(skillName);
        if (info != null) {
            role.removeSingleTool(info.toolName());
        }
        mine.remove(skillName);
        logger.info("[{}] skill tool removed: {}", roleId, skillName);
        return "Success: skill '" + skillName + "' has been removed from " + roleId + ".";
    }

    /** List the skill tools already added to a role. */
    public List<Map<String, String>> listRoleSkills(AgentRole role) {
        Set<String> mine = roleSkills.getOrDefault(role.roleId, new LinkedHashSet<>());
        List<Map<String, String>> result = new ArrayList<>();
        for (String n : mine) {
            SkillInfo info = skills.get(n);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", n);
            m.put("description", truncate(info != null ? info.description : "", 120));
            result.add(m);
        }
        return result;
    }

    /** Assemble the complete skill content: frontmatter summary + full SKILL.md text + related file list. */
    private static String readSkillContent(SkillInfo info) {
        String body = info.readSkillMd();
        List<String> related = info.listRelatedFiles();
        List<String> parts = new ArrayList<>();
        parts.add("Skill: " + info.name);
        parts.add("Directory: " + info.path);
        parts.add("Description: " + info.description);
        parts.add("");
        parts.add("════ SKILL.md Full Text ════");
        parts.add(body.isEmpty() ? "(SKILL.md is empty)" : body);
        if (!related.isEmpty()) {
            parts.add("");
            parts.add("════ Related Files (accessible via run_command / file tools) ════");
            for (String p : related) {
                parts.add("- " + p);
            }
        }
        parts.add("(End of skill instructions. Follow the steps above; when you need to run scripts, use the run_command tool on your personal computer.)");
        return String.join("\n", parts);
    }

    /** Parse the frontmatter name/description from SKILL.md text. */
    static String[] parseFrontmatter(String text) {
        if (text == null || !text.startsWith("---")) {
            return new String[]{null, ""};
        }
        int end = text.indexOf("\n---", 3);
        if (end == -1) {
            end = text.indexOf("...", 3);
        }
        String fm = end != -1 ? text.substring(3, end) : text.substring(3);
        String name = null;
        String desc = "";
        for (String line : fm.split("\n")) {
            line = line.strip();
            if (line.startsWith("name:")) {
                name = line.substring("name:".length()).strip().replaceAll("^[\"']|[\"']$", "");
            } else if (line.startsWith("description:") && desc.isEmpty()) {
                desc = line.substring("description:".length()).strip().replaceAll("^[\"']|[\"']$", "");
            }
        }
        return new String[]{name, desc};
    }

    private static Map<String, Object> emptySchema() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", new LinkedHashMap<>());
        return m;
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }
}
