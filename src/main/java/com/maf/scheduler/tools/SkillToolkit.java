package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.utils.Json;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.core.ToolRegistry.ToolHandler;
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
 * 技能工具类 (Skill ToolKit) — Python 版 skill_toolkit.py.
 *
 * 第三种工具格式: 基于 SKILL.md 的技能包. 每个技能 = 一个目录, 内含
 * SKILL.md (frontmatter: name/description + 使用指引) 以及可选的 scripts/.
 *
 * 包含管理工具: skill_search / skill_list / skill_add / skill_remove /
 * skill_my_skills (与 mcp_manager 对称).
 */
public final class SkillToolkit {

    private static final Logger logger = LoggerFactory.getLogger(SkillToolkit.class);

    private static final Pattern TOOL_NAME_RE = Pattern.compile("[^a-z0-9_]+");

    /** 一个技能包的元信息. */
    public static final class SkillInfo {
        public final String name;
        public final String description;
        public final Path path;

        public SkillInfo(String name, String description, Path path) {
            this.name = name;
            this.description = description;
            this.path = path;
        }

        /** 转成合法工具名 (小写下划线, 空格/连字符 → 下划线). */
        public String toolName() {
            String n = TOOL_NAME_RE.matcher(name.toLowerCase()).replaceAll("_");
            n = n.replaceAll("^_+|_+$", "");
            return n.isEmpty() ? "skill" : n;
        }

        /** 读取 SKILL.md 全文 (不存在返回空串). */
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

        /** 列出技能目录下的相关文件 (scripts/references/assets), 相对路径排序. */
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

    /** 从 SKILL.md 文本解析 frontmatter 的 name/description. */
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

    /** 技能库管理器: 扫描 SKILL.md, 按需把技能注册为角色工具. */
    public static final class SkillManager {
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

        /** 扫描技能库全部 SKILL.md 并解析 frontmatter. 幂等. */
        public Map<String, SkillInfo> ensureLoaded() {
            if (loaded) {
                return skills;
            }
            if (!Files.isDirectory(skillsDir)) {
                logger.warn("技能库目录不存在: {} (clone anbeime/skill 后复制 skills/ 过来)", skillsDir);
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
                    // 同名冲突: 保留第一个, 后续加序号
                    String base = name;
                    int idx = 2;
                    while (skills.containsKey(name)) {
                        name = base + "-" + idx;
                        idx++;
                    }
                    skills.put(name, new SkillInfo(name, fm[1], md.getParent()));
                }
            } catch (IOException e) {
                logger.warn("扫描技能库失败: {}", e.getMessage());
            }
            loaded = true;
            logger.info("SkillManager: 已加载 {} 个技能 (来自 {})", skills.size(), skillsDir);
            return skills;
        }

        /** 列出技能库中全部技能. */
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

        /** 按关键词搜索技能 (匹配名称或描述). */
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

        /** 为角色安装一个技能工具. */
        public String addSkill(AgentRole role, String skillName) {
            ensureLoaded();
            SkillInfo info = skills.get(skillName);
            if (info == null) {
                return "错误: 技能库中没有名为 '" + skillName + "' 的技能. 可用 skill_search / skill_list 查看全部技能.";
            }
            String roleId = role.roleId;
            Set<String> mine = roleSkills.computeIfAbsent(roleId, k -> new LinkedHashSet<>());
            if (mine.contains(skillName)) {
                return "技能 '" + skillName + "' 已添加给 " + roleId + ", 无需重复添加.";
            }
            SkillInfo infoRef = info;
            role.addSingleTool(info.toolName(),
                    truncate(info.description == null || info.description.isEmpty()
                            ? "技能: " + info.name : info.description, 300),
                    TalkToolkit.emptySchema(),
                    args -> readSkillContent(infoRef),
                    "skill:" + info.name);
            mine.add(skillName);
            logger.info("[{}] 技能工具已添加: {} (来自 {})", roleId, skillName, info.path);
            return "成功: 技能 '" + skillName + "' 已安装到 " + roleId + " (" + truncate(info.description, 60) + "...)";
        }

        /** 从角色移除一个技能工具. */
        public String removeSkill(AgentRole role, String skillName) {
            String roleId = role.roleId;
            Set<String> mine = roleSkills.getOrDefault(roleId, new LinkedHashSet<>());
            if (!mine.contains(skillName)) {
                return "技能 '" + skillName + "' 尚未添加给 " + roleId + ", 无需移除.";
            }
            SkillInfo info = skills.get(skillName);
            if (info != null) {
                role.removeSingleTool(info.toolName());
            }
            mine.remove(skillName);
            logger.info("[{}] 技能工具已移除: {}", roleId, skillName);
            return "成功: 技能 '" + skillName + "' 已从 " + roleId + " 移除.";
        }

        /** 列出角色已添加的技能工具. */
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
    }

    /** 拼接技能完整内容: frontmatter 摘要 + SKILL.md 全文 + 相关文件清单. */
    private static String readSkillContent(SkillInfo info) {
        String body = info.readSkillMd();
        List<String> related = info.listRelatedFiles();
        List<String> parts = new ArrayList<>();
        parts.add("技能: " + info.name);
        parts.add("目录: " + info.path);
        parts.add("描述: " + info.description);
        parts.add("");
        parts.add("════ SKILL.md 全文 ════");
        parts.add(body.isEmpty() ? "(SKILL.md 为空)" : body);
        if (!related.isEmpty()) {
            parts.add("");
            parts.add("════ 相关文件 (可用 run_command / 文件工具访问) ════");
            for (String p : related) {
                parts.add("- " + p);
            }
        }
        parts.add("(技能指引结束, 请按上述步骤执行; 需要执行脚本时用个人电脑的 run_command 工具)");
        return String.join("\n", parts);
    }

    /** 把技能管理操作打包成 LLM 可调用的工具类. */
    public static ToolKit createSkillManagerToolkit(SkillManager manager) {
        ToolKit tk = new ToolKit("skill_manager", "技能管理: 搜索/添加/移除 SKILL.md 技能工具");
        tk.bind("manager", manager);

        ToolHandler skillSearch = args -> {
            String kw = Json.str(args, "keyword", "").strip();
            if (kw.isEmpty()) {
                return "请提供 keyword 搜索词.";
            }
            List<Map<String, String>> hits = manager.searchSkills(kw);
            if (hits.isEmpty()) {
                return "没有匹配 '" + kw + "' 的技能. 可用 skill_list 查看全部.";
            }
            List<String> lines = new ArrayList<>();
            lines.add("搜索 '" + kw + "' 找到 " + hits.size() + " 个技能:");
            for (Map<String, String> h : hits) {
                lines.add("- " + h.get("name") + ": " + h.get("description"));
            }
            return String.join("\n", lines);
        };

        ToolHandler skillList = args -> {
            List<Map<String, String>> avail = manager.listAvailable();
            if (avail.isEmpty()) {
                return "暂无可用技能 (技能库为空, 请确认 data/skills/ 存在).";
            }
            List<String> lines = new ArrayList<>();
            lines.add("技能库共有 " + avail.size() + " 个技能:");
            for (Map<String, String> a : avail) {
                lines.add("- " + a.get("name") + ": " + a.get("description"));
            }
            return String.join("\n", lines);
        };

        ToolHandler skillAdd = args -> {
            String name = Json.str(args, "skill_name", "").strip();
            if (name.isEmpty()) {
                return "请提供 skill_name.";
            }
            return manager.addSkill(role(tk), name);
        };

        ToolHandler skillRemove = args -> {
            String name = Json.str(args, "skill_name", "").strip();
            if (name.isEmpty()) {
                return "请提供 skill_name.";
            }
            return manager.removeSkill(role(tk), name);
        };

        ToolHandler skillMySkills = args -> {
            List<Map<String, String>> mine = manager.listRoleSkills(role(tk));
            if (mine.isEmpty()) {
                return "你还没有添加任何技能. 可用 skill_search / skill_list 寻找, 用 skill_add 添加.";
            }
            List<String> lines = new ArrayList<>();
            lines.add("你已添加 " + mine.size() + " 个技能:");
            for (Map<String, String> m : mine) {
                lines.add("- " + m.get("name") + ": " + m.get("description"));
            }
            return String.join("\n", lines);
        };

        Map<String, Object> keywordSchema = new LinkedHashMap<>();
        keywordSchema.put("type", "object");
        keywordSchema.put("properties", Map.of(
                "keyword", TalkToolkit.mapOf("string", "搜索关键词, 如 ppt/video/pdf/写作")));
        keywordSchema.put("required", List.of("keyword"));

        Map<String, Object> nameSchema = new LinkedHashMap<>();
        nameSchema.put("type", "object");
        nameSchema.put("properties", Map.of(
                "skill_name", TalkToolkit.mapOf("string", "要添加的技能名, 如 pptx-generator")));
        nameSchema.put("required", List.of("skill_name"));

        tk.addPythonTool("skill_search",
                "搜索技能库中的技能 (按名称或描述关键词). 先搜索找到合适技能, 再用 skill_add 添加给自己.",
                keywordSchema, skillSearch);
        tk.addPythonTool("skill_list",
                "列出技能库中全部可用技能 (名称+简述). 查看有哪些技能可用.",
                TalkToolkit.emptySchema(), skillList);
        tk.addPythonTool("skill_add",
                "为当前角色添加一个技能. 添加后即可在任务中调用该技能 (获得其完整使用指引).",
                nameSchema, skillAdd);
        tk.addPythonTool("skill_remove",
                "从当前角色移除一个已添加的技能.",
                nameSchema, skillRemove);
        tk.addPythonTool("skill_my_skills",
                "查看当前角色已添加的技能列表.",
                TalkToolkit.emptySchema(), skillMySkills);
        return tk;
    }

    private static AgentRole role(ToolKit tk) {
        AgentRole r = (AgentRole) tk.require("role", "角色");
        return r;
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** 将当前角色绑定到 skill_manager 工具类. */
    public static void bindRoleToToolkit(ToolKit toolkit, AgentRole role) {
        toolkit.bind("role", role);
    }
}
