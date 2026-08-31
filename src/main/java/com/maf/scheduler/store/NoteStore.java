package com.maf.scheduler.store;

import com.maf.scheduler.core.PathManager;
import com.maf.scheduler.event.TimeEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 笔记存储 (NoteStore) — 基于文件的笔记与日记存储 (Python 版 note_store.py).
 *
 * 每个 Role 绑定一个 NoteStore 实例, 内容按角色隔离:
 *   data/notes/&lt;role_id&gt;/notes/&lt;标题&gt;.md      # 普通笔记
 *   data/notes/&lt;role_id&gt;/summaries/&lt;day&gt;.md   # 每日总结
 */
public class NoteStore {

    private static final Logger logger = LoggerFactory.getLogger(NoteStore.class);

    private static final Pattern SANITIZE_RE = Pattern.compile("[\\\\/:*?\"<>|#%\\s'`$;&]+");

    private final Path base;
    public final String roleId;
    private final TimeEventBus timeManager;
    private final Path dir;
    public final Path notePath;
    public final Path summaryPath;

    public NoteStore(String baseDir, String roleId, TimeEventBus timeManager) {
        this.base = baseDir != null ? Paths.get(baseDir)
                : PathManager.createDefault().dataDir().resolve("notes");
        this.roleId = roleId != null ? roleId : "";
        this.timeManager = timeManager;
        this.dir = this.base.resolve(roleId);
        this.notePath = this.dir.resolve("notes");
        this.summaryPath = this.dir.resolve("summaries");
        try {
            Files.createDirectories(notePath);
            Files.createDirectories(summaryPath);
        } catch (IOException e) {
            throw new RuntimeException("创建笔记目录失败: " + dir, e);
        }
    }

    // ── 路径工具 ──────────────────────────────────────────

    /** 清洗标题为合法文件名. 非法字符替换为下划线. */
    public static String sanitizeTitle(String title) {
        String cleaned = SANITIZE_RE.matcher(title == null ? "" : title.strip()).replaceAll("_");
        return cleaned.isEmpty() ? "untitled" : cleaned;
    }

    /** 笔记文件名. */
    public static String noteFilename(String title) {
        return sanitizeTitle(title) + ".md";
    }

    /** 每日总结文件名. */
    public static String summaryFilename(int day) {
        return day + ".md";
    }

    // ── 底层读写 ──────────────────────────────────────────

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败: " + path, e);
        }
    }

    private String read(Path path) throws java.io.FileNotFoundException {
        if (!Files.exists(path)) {
            throw new java.io.FileNotFoundException("笔记文件不存在: " + path);
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + path, e);
        }
    }

    private List<Path> listMdFiles(Path path) {
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try (var stream = Files.list(path)) {
            List<Path> out = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(out::add);
            return out;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    // ── 提醒 (笔记 = 任务统一概念) ─────────────────────────

    /** 注册笔记提醒: 到指定 Tick 向本角色发送提醒事件. */
    public TimeEventBus.ScheduledTask scheduleReminder(String title, int tick, Integer day) {
        if (timeManager == null) {
            throw new IllegalArgumentException(
                    "当前笔记存储未绑定 TimeEventBus, 无法注册提醒 (请通过 AgentRole.noteStore 使用)");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("note_title", title);
        return timeManager.scheduleTask("[笔记提醒] " + title, roleId, tick, day, payload);
    }

    /** 取消该标题笔记的提醒. */
    public boolean cancelReminder(String title) {
        if (timeManager == null) {
            return false;
        }
        boolean removed = false;
        for (TimeEventBus.ScheduledTask t : timeManager.listTasks(roleId)) {
            Object noteTitle = t.payload.get("note_title");
            if (title.equals(noteTitle)) {
                timeManager.cancelTask(t.taskId);
                removed = true;
            }
        }
        return removed;
    }

    /** 查询笔记的提醒信息 (未触发). 返回 {"day", "tick"} 或 null. */
    public Map<String, Object> getReminder(String title) {
        if (timeManager == null) {
            return null;
        }
        for (TimeEventBus.ScheduledTask t : timeManager.listTasks(roleId)) {
            Object noteTitle = t.payload.get("note_title");
            if (title.equals(noteTitle)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("day", t.day);
                m.put("tick", t.targetTick);
                return m;
            }
        }
        return null;
    }

    // ── 笔记操作 ──────────────────────────────────────────

    /** 写笔记. 已存在则覆盖. 填 remindTick 后到点发送提醒事件. */
    public Path writeNote(String title, String content, Integer remindTick, Integer remindDay) {
        Path path = notePath.resolve(noteFilename(title));
        write(path, content);
        if (remindTick != null) {
            TimeEventBus.ScheduledTask task = scheduleReminder(title, remindTick, remindDay);
            logger.info("[{}] 笔记已写入并设置提醒: {} (第 {} 天 Tick {})",
                    roleId, path.getFileName(), task.day, task.targetTick);
        } else {
            logger.info("[{}] 笔记已写入: {}", roleId, path.getFileName());
        }
        return path;
    }

    /** 编辑已有笔记 (覆盖内容). 不存在则创建. */
    public Path editNote(String title, String content, Integer remindTick, Integer remindDay) {
        Path path = notePath.resolve(noteFilename(title));
        write(path, content);
        if (remindTick != null) {
            cancelReminder(title);
            TimeEventBus.ScheduledTask task = scheduleReminder(title, remindTick, remindDay);
            logger.info("[{}] 笔记已编辑并重置提醒: {} (第 {} 天 Tick {})",
                    roleId, path.getFileName(), task.day, task.targetTick);
        } else {
            logger.info("[{}] 笔记已编辑: {}", roleId, path.getFileName());
        }
        return path;
    }

    /** 列出所有笔记标题 (不含每日总结). 按文件名排序. */
    public List<String> listNotes() {
        List<String> titles = new ArrayList<>();
        for (Path p : listMdFiles(notePath)) {
            String name = p.getFileName().toString();
            titles.add(name.substring(0, name.length() - ".md".length()));
        }
        return titles;
    }

    /** 读取笔记内容; 不存在返回 null. */
    public String readNote(String title) {
        Path path = notePath.resolve(noteFilename(title));
        try {
            return read(path);
        } catch (java.io.FileNotFoundException e) {
            return null;
        }
    }

    /** 删除笔记 (真实删除文件 + 取消关联提醒). 返回是否删除成功. */
    public boolean deleteNote(String title) {
        cancelReminder(title);
        Path path = notePath.resolve(noteFilename(title));
        if (!Files.exists(path)) {
            return false;
        }
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new RuntimeException("删除笔记失败: " + path, e);
        }
        logger.info("[{}] 笔记已删除: {}", roleId, path.getFileName());
        return true;
    }

    // ── 每日总结 (作息系统, 按天序号存储) ─────────────────

    /** 保存某一天的总结. */
    public Path saveSummary(String content, Integer day) {
        int d = day != null ? day : 1;
        Path path = summaryPath.resolve(summaryFilename(d));
        write(path, content);
        logger.info("[{}] 第 {} 天总结已保存: {}", roleId, d, path.getFileName());
        return path;
    }

    /** 读取指定天的总结; 不存在返回 null. */
    public String getSummary(Integer day) {
        int d = day != null ? day : 1;
        try {
            return read(summaryPath.resolve(summaryFilename(d)));
        } catch (java.io.FileNotFoundException e) {
            return null;
        }
    }

    /** 读取最近一次总结 (beforeDay 只找严格早于该天的). */
    public String getLatestSummary(Integer beforeDay) {
        List<int[]> candidates = new ArrayList<>();  // [day, index]
        List<Path> files = listMdFiles(summaryPath);
        for (Path p : files) {
            String name = p.getFileName().toString();
            try {
                int d = Integer.parseInt(name.substring(0, name.length() - ".md".length()));
                candidates.add(new int[]{d, files.indexOf(p)});
            } catch (NumberFormatException ignored) {
            }
        }
        candidates.sort(Comparator.comparingInt((int[] a) -> a[0]).reversed());
        for (int[] c : candidates) {
            if (beforeDay == null || c[0] < beforeDay) {
                try {
                    String content = read(summaryPath.resolve(files.get(c[1]).getFileName()));
                    if (content != null) {
                        return content;
                    }
                } catch (java.io.FileNotFoundException ignored) {
                }
            }
        }
        return null;
    }
}
