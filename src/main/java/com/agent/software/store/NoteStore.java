package com.agent.software.store;

import com.agent.software.event.TimeEventBus;
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
 * Note storage (NoteStore) — file-based note and diary storage (Python version note_store.py).
 *
 * Each Role is bound to one NoteStore instance; content is isolated per role:
 *   data/notes/&lt;role_id&gt;/notes/&lt;title&gt;.md      # normal notes
 *   data/notes/&lt;role_id&gt;/summaries/&lt;day&gt;.md   # daily summaries
 *
 * The default base directory is data/notes inside the project (consistent with .gitignore
 * and the project-wide ./data/* layout); in multi-AgentSystem scenarios AgentSystem passes
 * a per-system data directory, so systems do not interfere with each other.
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
        this.base = baseDir != null ? Paths.get(baseDir) : Paths.get("data", "notes");
        this.roleId = roleId != null ? roleId : "";
        this.timeManager = timeManager;
        this.dir = this.base.resolve(roleId);
        this.notePath = this.dir.resolve("notes");
        this.summaryPath = this.dir.resolve("summaries");
        try {
            Files.createDirectories(notePath);
            Files.createDirectories(summaryPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create note directories: " + dir, e);
        }
    }

    // ── Path utilities ──────────────────────────────────────────

    /** Sanitize a title into a valid file name. Illegal characters are replaced with underscores. */
    public static String sanitizeTitle(String title) {
        String cleaned = SANITIZE_RE.matcher(title == null ? "" : title.strip()).replaceAll("_");
        return cleaned.isEmpty() ? "untitled" : cleaned;
    }

    /** Note file name. */
    public static String noteFilename(String title) {
        return sanitizeTitle(title) + ".md";
    }

    /** Daily summary file name. */
    public static String summaryFilename(int day) {
        return day + ".md";
    }

    // ── Low-level read/write ──────────────────────────────────────────

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + path, e);
        }
    }

    private String read(Path path) throws java.io.FileNotFoundException {
        if (!Files.exists(path)) {
            throw new java.io.FileNotFoundException("Note file does not exist: " + path);
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + path, e);
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

    // ── Reminders (notes = unified task concept) ─────────────────────────

    /** Register a note reminder: send a reminder event to this role at the specified tick. */
    public TimeEventBus.ScheduledTask scheduleReminder(String title, int tick, Integer day) {
        if (timeManager == null) {
            throw new IllegalArgumentException(
                    "The current note store is not bound to a TimeEventBus, cannot register reminders (use it via AgentRole.noteStore)");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("note_title", title);
        return timeManager.scheduleTask("[Note Reminder] " + title, roleId, tick, day, payload);
    }

    /** Cancel the reminder for the note with this title. */
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

    /** Query the reminder info of a note (not yet triggered). Returns {"day", "tick"} or null. */
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

    // ── Note operations ──────────────────────────────────────────

    /** Write a note. Overwrites if it already exists. If remindTick is set, a reminder event is sent when the time comes. */
    public Path writeNote(String title, String content, Integer remindTick, Integer remindDay) {
        Path path = notePath.resolve(noteFilename(title));
        write(path, content);
        if (remindTick != null) {
            TimeEventBus.ScheduledTask task = scheduleReminder(title, remindTick, remindDay);
            logger.info("[{}] Note written with reminder set: {} (day {}, tick {})",
                    roleId, path.getFileName(), task.day, task.targetTick);
        } else {
            logger.info("[{}] Note written: {}", roleId, path.getFileName());
        }
        return path;
    }

    /** Edit an existing note (overwrites the content). Creates it if it does not exist. */
    public Path editNote(String title, String content, Integer remindTick, Integer remindDay) {
        Path path = notePath.resolve(noteFilename(title));
        write(path, content);
        if (remindTick != null) {
            cancelReminder(title);
            TimeEventBus.ScheduledTask task = scheduleReminder(title, remindTick, remindDay);
            logger.info("[{}] Note edited with reminder reset: {} (day {}, tick {})",
                    roleId, path.getFileName(), task.day, task.targetTick);
        } else {
            logger.info("[{}] Note edited: {}", roleId, path.getFileName());
        }
        return path;
    }

    /** List all note titles (excluding daily summaries). Sorted by file name. */
    public List<String> listNotes() {
        List<String> titles = new ArrayList<>();
        for (Path p : listMdFiles(notePath)) {
            String name = p.getFileName().toString();
            titles.add(name.substring(0, name.length() - ".md".length()));
        }
        return titles;
    }

    /** Read the note content; returns null if it does not exist. */
    public String readNote(String title) {
        Path path = notePath.resolve(noteFilename(title));
        try {
            return read(path);
        } catch (java.io.FileNotFoundException e) {
            return null;
        }
    }

    /** Delete a note (really deletes the file + cancels the associated reminder). Returns whether deletion succeeded. */
    public boolean deleteNote(String title) {
        cancelReminder(title);
        Path path = notePath.resolve(noteFilename(title));
        if (!Files.exists(path)) {
            return false;
        }
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete note: " + path, e);
        }
        logger.info("[{}] Note deleted: {}", roleId, path.getFileName());
        return true;
    }

    // ── Daily summaries (daily routine system, stored by day number) ─────────────────

    /** Save the summary of a given day. */
    public Path saveSummary(String content, Integer day) {
        int d = day != null ? day : 1;
        Path path = summaryPath.resolve(summaryFilename(d));
        write(path, content);
        logger.info("[{}] Summary for day {} saved: {}", roleId, d, path.getFileName());
        return path;
    }

    /** Read the summary of a given day; returns null if it does not exist. */
    public String getSummary(Integer day) {
        int d = day != null ? day : 1;
        try {
            return read(summaryPath.resolve(summaryFilename(d)));
        } catch (java.io.FileNotFoundException e) {
            return null;
        }
    }

    /** Read the most recent summary (beforeDay only looks for summaries strictly earlier than that day). */
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
