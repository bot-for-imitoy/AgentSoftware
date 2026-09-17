package com.agent.software.tool.skill;

import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.infra.json.JsonCodec;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.SkillId;
import com.agent.software.kernel.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 目录扫描形态的技能库：解析 SKILL.md 得到技能，并记录角色授权关系。
 *
 * <p>目录布局（对齐 master {@code SkillManager}）：
 * <pre>
 *   &lt;data&gt;/skills/
 *     &lt;skillId&gt;/SKILL.md        # YAML frontmatter（name / description）+ 正文
 *     &lt;skillId&gt;/scripts/...     # 同目录其它文件作为附件，相对路径进 files
 *     grants.json               # {"roleId": ["skillId", ...]}
 * </pre>
 *
 * <p>技能目录不存在时返回空列表（不抛）；授权文件写入走 {@code .tmp + move} 原子替换；
 * 扫描与授权读写都在同一把锁内，避免并发下拿到半更新状态。
 */
public final class JsonSkillLibrary implements SkillLibrary {

    private static final Logger log = LoggerFactory.getLogger(JsonSkillLibrary.class);

    private static final String SKILL_FILE = "SKILL.md";
    private static final String GRANTS_FILE = "grants.json";

    private final AppPaths paths;
    private final Path skillsDir;
    private final Path grantsFile;

    /** 契约只给了 AppPaths 构造参数，因此内部自建 Jackson 编解码器（与 ConfigLoader 同做法）。 */
    private final JsonCodec json = new JacksonJsonCodec();

    private final Object lock = new Object();

    /** 绑定数据路径（技能目录与授权文件位置）。 */
    public JsonSkillLibrary(AppPaths paths) {
        this.paths = paths;
        this.skillsDir = paths.dataFile("skills");
        this.grantsFile = paths.dataFile("skills", GRANTS_FILE);
    }

    @Override
    public List<Skill> available() {
        synchronized (lock) {
            return scan();
        }
    }

    @Override
    public List<Skill> search(String keyword) {
        String kw = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        if (kw.isEmpty()) {
            return List.of();
        }
        List<Skill> hits = new ArrayList<>();
        for (Skill skill : available()) {
            String haystack = (Text.orEmpty(skill.name()) + "\n"
                    + Text.orEmpty(skill.description()) + "\n"
                    + Text.orEmpty(skill.body())).toLowerCase(Locale.ROOT);
            if (haystack.contains(kw)) {
                hits.add(skill);
            }
        }
        return hits;
    }

    @Override
    public List<Skill> ownedBy(RoleId owner) {
        if (owner == null) {
            return List.of();
        }
        synchronized (lock) {
            Set<String> granted = grants().getOrDefault(owner.value(), Set.of());
            List<Skill> out = new ArrayList<>();
            for (Skill skill : scan()) {
                if (granted.contains(skill.id().value())) {
                    out.add(skill);
                }
            }
            return out;
        }
    }

    @Override
    public void grant(RoleId owner, SkillId id) {
        if (owner == null || id == null) {
            return;
        }
        synchronized (lock) {
            Map<String, Set<String>> grants = grants();
            grants.computeIfAbsent(owner.value(), k -> new LinkedHashSet<>()).add(id.value());
            saveGrants(grants);
        }
    }

    @Override
    public void revoke(RoleId owner, SkillId id) {
        if (owner == null || id == null) {
            return;
        }
        synchronized (lock) {
            Map<String, Set<String>> grants = grants();
            Set<String> mine = grants.get(owner.value());
            if (mine != null && mine.remove(id.value())) {
                if (mine.isEmpty()) {
                    grants.remove(owner.value());
                }
                saveGrants(grants);
            }
        }
    }

    // ── 内部：扫描 SKILL.md ─────────────────────────────────────────

    /** 扫描技能目录；目录不存在或读取失败时返回已成功解析的部分（不抛）。 */
    private List<Skill> scan() {
        if (!Files.isDirectory(skillsDir)) {
            return List.of();
        }
        List<Skill> out = new ArrayList<>();
        try (var stream = Files.list(skillsDir)) {
            List<Path> dirs = new ArrayList<>();
            stream.filter(Files::isDirectory).sorted().forEach(dirs::add);
            for (Path dir : dirs) {
                Path md = dir.resolve(SKILL_FILE);
                if (!Files.isRegularFile(md)) {
                    continue;
                }
                String text;
                try {
                    text = Files.readString(md, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("技能文件读取失败，已跳过: {} ({})", md, e.getMessage());
                    continue;
                }
                String dirName = dir.getFileName().toString();
                Parsed parsed = parse(text, dirName);
                out.add(new Skill(new SkillId(dirName), parsed.name(), parsed.description(),
                        parsed.body(), listFiles(dir)));
            }
        } catch (IOException e) {
            log.warn("技能目录扫描失败: {} ({})", skillsDir, e.getMessage());
        }
        return out;
    }

    /** 列出技能目录下除根 SKILL.md 之外的全部文件（相对路径，排序稳定）。 */
    private static List<String> listFiles(Path dir) {
        List<String> files = new ArrayList<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(p -> {
                        String rel = dir.relativize(p).toString();
                        if (!SKILL_FILE.equals(rel)) {
                            files.add(rel);
                        }
                    });
        } catch (IOException e) {
            log.warn("技能附件扫描失败: {} ({})", dir, e.getMessage());
        }
        files.sort(String::compareTo);
        return files;
    }

    /** 解析后的技能内容。 */
    private record Parsed(String name, String description, String body) {
    }

    /**
     * 解析 SKILL.md：{@code ---} 之间为 YAML frontmatter，只取 name/description；其余为正文。
     * 没有 frontmatter 或没有 name 时，回退用目录名当技能名。
     */
    static Parsed parse(String text, String fallbackName) {
        String src = Text.orEmpty(text);
        if (!src.startsWith("---")) {
            return new Parsed(fallbackName, "", src.strip());
        }
        int end = src.indexOf("\n---", 3);
        String frontmatter;
        String body;
        if (end < 0) {
            frontmatter = src.substring(3);
            body = "";
        } else {
            frontmatter = src.substring(3, end);
            int bodyStart = src.indexOf('\n', end + 1);
            body = bodyStart < 0 ? "" : src.substring(bodyStart + 1).strip();
        }
        String name = null;
        String description = "";
        for (String line : frontmatter.split("\n")) {
            String trimmed = line.strip();
            if (name == null && trimmed.startsWith("name:")) {
                name = unquote(trimmed.substring("name:".length()).strip());
            } else if (description.isEmpty() && trimmed.startsWith("description:")) {
                description = unquote(trimmed.substring("description:".length()).strip());
            }
        }
        if (Text.isBlank(name)) {
            name = fallbackName;
        }
        return new Parsed(name, description, body);
    }

    private static String unquote(String value) {
        return value.replaceAll("^[\"']|[\"']$", "").strip();
    }

    // ── 内部：授权关系落盘 ─────────────────────────────────────────

    /** 读取 grants.json；不存在或损坏时返回空表（损坏只 warn，不抛）。 */
    private Map<String, Set<String>> grants() {
        if (!Files.isRegularFile(grantsFile)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> root = json.readMap(Files.readString(grantsFile, StandardCharsets.UTF_8));
            Map<String, Set<String>> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : root.entrySet()) {
                Set<String> ids = new LinkedHashSet<>();
                if (entry.getValue() instanceof List<?> list) {
                    for (Object id : list) {
                        if (id != null) {
                            ids.add(String.valueOf(id));
                        }
                    }
                }
                out.put(entry.getKey(), ids);
            }
            return out;
        } catch (Exception e) {
            log.warn("技能授权文件损坏，按空处理: {} ({})", grantsFile, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void saveGrants(Map<String, Set<String>> grants) {
        Path file = paths.ensure(grantsFile);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : grants.entrySet()) {
            root.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        try {
            Files.writeString(tmp, json.write(root), StandardCharsets.UTF_8);
            move(tmp, file);
        } catch (IOException e) {
            throw new DomainError("skill.grants.write.failed", "技能授权写入失败: " + file, e);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
