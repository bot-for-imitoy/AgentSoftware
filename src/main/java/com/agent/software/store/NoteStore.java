package com.agent.software.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 笔记存储：一个角色一个目录，一条笔记一个 markdown 文件。
 *
 * <pre>
 *   &lt;base&gt;/&lt;role_id&gt;/notes/&lt;title&gt;.md
 * </pre>
 *
 * <p>base 默认是 {@code <dataDir>/notes}（{@link com.agent.software.AgentSystem} 传进来），
 * 多实例/测试各用各的 dataDir，互不干扰。
 *
 * <p>笔记只做增删改查（纯文本、跨天保留）：跨天的对话上下文会在下班时
 * {@code Context.forgetAll()} 移出 prompt，所以"要记住的事"必须落到笔记里。
 * 定时提醒不在笔记里做 —— 那是 {@code task} 工具包的职责（真正的排期事件）。
 *
 * <p>线程契约：单角色单 worker 调用，但列表/读文件本身是只读的，多线程调用也安全。
 */
public class NoteStore {

    private static final Logger logger = LoggerFactory.getLogger(NoteStore.class);

    /** 文件名非法字符（含空白）统一替换成下划线。 */
    private static final Pattern SANITIZE_RE = Pattern.compile("[\\\\/:*?\"<>|#%\\s'`$;&]+");
    private static final String SUFFIX = ".md";

    private final String roleId;
    private final Path dir;

    public NoteStore(String baseDir, String roleId) {
        this(Paths.get(baseDir == null || baseDir.isBlank() ? "data/notes" : baseDir), roleId);
    }

    public NoteStore(Path baseDir, String roleId) {
        Path base = baseDir == null ? Paths.get("data", "notes") : baseDir;
        this.roleId = roleId == null ? "" : roleId;
        this.dir = base.resolve(this.roleId).resolve("notes");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create note directory: " + dir, e);
        }
    }

    // ── 路径 ────────────────────────────────────────────────────

    /** 标题 → 合法文件名：非法字符（含路径分隔符/空白）换成下划线，去掉首尾的点/下划线，并去掉多写的 .md。 */
    public static String sanitizeTitle(String title) {
        String cleaned = SANITIZE_RE.matcher(title == null ? "" : title.strip()).replaceAll("_");
        // 首尾的点/下划线一律去掉：既防 "../../etc/passwd" 这类路径花样，也让 " a/b " 变成 "a_b"
        cleaned = cleaned.replaceAll("^[._\\s]+", "").replaceAll("[._\\s]+$", "");
        if (cleaned.length() > 3 && cleaned.regionMatches(true, cleaned.length() - 3, ".md", 0, 3)) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).replaceAll("[._\\s]+$", "");
        }
        return cleaned.isEmpty() ? "untitled" : cleaned;
    }

    public static String noteFilename(String title) {
        return sanitizeTitle(title) + SUFFIX;
    }

    public String roleId() {
        return roleId;
    }

    public Path dir() {
        return dir;
    }

    /** 某条笔记的文件路径（不一定存在）。 */
    public Path path(String title) {
        return dir.resolve(noteFilename(title));
    }

    public boolean exists(String title) {
        return Files.exists(path(title));
    }

    // ── 增删改查 ────────────────────────────────────────────────

    /** 写笔记：不存在就新建，存在就覆盖。 */
    public Path writeNote(String title, String content) {
        Path path = path(title);
        write(path, content == null ? "" : content);
        logger.info("NoteStore[{}] note written: {}", roleId, path.getFileName());
        return path;
    }

    /**
     * 改笔记：覆盖已有内容；不存在时按写入处理（新建）。
     *
     * @return true = 覆盖了已有笔记，false = 原来不存在（新建）
     */
    public boolean editNote(String title, String content) {
        Path path = path(title);
        boolean existed = Files.exists(path);
        write(path, content == null ? "" : content);
        logger.info("NoteStore[{}] note {}: {}", roleId, existed ? "edited" : "created", path.getFileName());
        return existed;
    }

    /** 读笔记；不存在返回 null（由工具层决定提示文案）。 */
    public String readNote(String title) {
        Path path = path(title);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read note: " + path, e);
        }
    }

    /** 删笔记；返回是否真的删掉了（不存在返回 false）。 */
    public boolean deleteNote(String title) {
        Path path = path(title);
        try {
            boolean removed = Files.deleteIfExists(path);
            if (removed) {
                logger.info("NoteStore[{}] note deleted: {}", roleId, path.getFileName());
            }
            return removed;
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete note: " + path, e);
        }
    }

    /** 所有笔记标题（按文件名排序）。 */
    public List<String> listNotes() {
        List<String> titles = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return titles;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(SUFFIX))
                    .sorted(Comparator.naturalOrder())
                    .forEach(n -> titles.add(n.substring(0, n.length() - SUFFIX.length())));
        } catch (IOException e) {
            logger.warn("NoteStore[{}] failed to list notes in {}", roleId, dir, e);
        }
        return titles;
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write note: " + path, e);
        }
    }
}
