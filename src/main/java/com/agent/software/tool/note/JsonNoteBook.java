package com.agent.software.tool.note;

import com.agent.software.agent.dialog.DailySummary;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JsonCodec;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.NoteId;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JSON 目录形态的笔记实现：每角色一份笔记文件，每日总结作为特殊笔记保存。
 *
 * <p>同时实现 {@link DailySummary}：提示词侧只认 agent 自己声明的那一个方法，
 * 不需要认识整个 {@link NoteBook}。
 *
 * <p>落盘形状 {@code {"notes":[{id,owner,title,body,remind_day,remind_tick,updated_at}]}}，
 * 写入走 {@code .tmp + move} 原子替换，避免进程中断留下半截 JSON。
 *
 * <p>并发：按 owner 分锁，不同角色的文件互不阻塞；同一角色的读改写串行。
 */
public final class JsonNoteBook implements NoteBook, DailySummary {

    private static final Logger log = LoggerFactory.getLogger(JsonNoteBook.class);

    private final AppPaths paths;
    private final JsonCodec json;

    /** 每个角色一把可重入锁（ReentrantLock 便于同线程内嵌套读，如 latestSummary → list）。 */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 绑定数据路径与 JSON 编解码器。 */
    public JsonNoteBook(AppPaths paths, JsonCodec json) {
        this.paths = paths;
        this.json = json;
    }

    @Override
    public List<Note> list(RoleId owner) {
        requireOwner(owner);
        ReentrantLock lock = lock(owner);
        lock.lock();
        try {
            List<Note> notes = load(owner);
            // 最近更新的排在前面；updatedAt 缺失的按最旧处理。
            notes.sort(Comparator.comparing(
                    (Note n) -> n.updatedAt() == null ? Instant.EPOCH : n.updatedAt()).reversed());
            return notes;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Note> read(RoleId owner, String title) {
        requireOwner(owner);
        if (Text.isBlank(title)) {
            return Optional.empty();
        }
        ReentrantLock lock = lock(owner);
        lock.lock();
        try {
            return find(load(owner), title);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void write(Note note) {
        requireNote(note);
        ReentrantLock lock = lock(note.owner());
        lock.lock();
        try {
            List<Note> notes = load(note.owner());
            // 同标题覆盖：保留原有 id，刷新 updatedAt；新笔记追加到末尾。
            upsert(notes, refresh(note, find(notes, note.title()).orElse(null)));
            save(note.owner(), notes);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void edit(Note note) {
        // 语义与 write 相同；update 采用原地替换，因此列表中的创建顺序不会被改写。
        write(note);
    }

    @Override
    public boolean delete(RoleId owner, String title) {
        requireOwner(owner);
        if (Text.isBlank(title)) {
            return false;
        }
        ReentrantLock lock = lock(owner);
        lock.lock();
        try {
            List<Note> notes = load(owner);
            boolean removed = notes.removeIf(n -> title.equals(n.title()));
            if (removed) {
                // 最后一条被删掉时直接移除文件（对齐 master NoteStore.deleteNote），
                // 不在磁盘上留下 {"notes":[]} 这种空壳。
                if (notes.isEmpty()) {
                    removeFile(owner);
                } else {
                    save(owner, notes);
                }
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void saveSummary(RoleId owner, int day, String body) {
        requireOwner(owner);
        write(new Note(newId(), owner, Note.SUMMARY_TITLE_PREFIX + day,
                Text.orEmpty(body), null, null, Instant.now()));
    }

    @Override
    public Optional<String> summary(RoleId owner, int day) {
        return read(owner, Note.SUMMARY_TITLE_PREFIX + day).map(Note::body);
    }

    @Override
    public Optional<String> latestSummary(RoleId owner, int beforeDay) {
        requireOwner(owner);
        Note best = null;
        int bestDay = Integer.MIN_VALUE;
        for (Note note : list(owner)) {
            if (!note.isSummary()) {
                continue;
            }
            OptionalInt day = summaryDay(note.title());
            // 按 day 数值比较（不是 updatedAt）：总结可能被补写或重排。
            if (day.isPresent() && day.getAsInt() < beforeDay && day.getAsInt() > bestDay) {
                bestDay = day.getAsInt();
                best = note;
            }
        }
        return best == null ? Optional.empty() : Optional.of(Text.orEmpty(best.body()));
    }

    // ── 内部：标题约定 ──────────────────────────────────────────────

    /** 从总结标题里解析天数（标题形如 {@code __summary_day_3}）；不符合约定时为 empty。 */
    private static OptionalInt summaryDay(String title) {
        if (title == null || !title.startsWith(Note.SUMMARY_TITLE_PREFIX)) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(title.substring(Note.SUMMARY_TITLE_PREFIX.length()).trim()));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    // ── 内部：文件读写 ──────────────────────────────────────────────

    private Path file(RoleId owner) {
        return paths.dataFile("notes", owner.value() + ".json");
    }

    /** 读取一个角色的全部笔记；文件不存在或损坏时返回空表（损坏只 warn，不抛）。 */
    private List<Note> load(RoleId owner) {
        Path file = file(owner);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> root = json.readMap(text);
            List<Note> out = new ArrayList<>();
            if (root.get("notes") instanceof List<?> raw) {
                for (Object item : raw) {
                    if (item instanceof Map<?, ?> map) {
                        Note note = fromMap(owner, map);
                        if (note != null) {
                            out.add(note);
                        }
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("笔记文件损坏，按空表处理: {} ({})", file, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 原子写：先写 .tmp 再 move，避免半截文件。 */
    private void save(RoleId owner, List<Note> notes) {
        Path file = paths.ensure(file(owner));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Note note : notes) {
            items.add(toMap(note));
        }
        root.put("notes", items);
        try {
            Files.writeString(tmp, json.write(root), StandardCharsets.UTF_8);
            move(tmp, file);
        } catch (IOException e) {
            throw new DomainError("note.write.failed", "笔记写入失败: " + file, e);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 删除角色的笔记文件（已不存在时视为成功）。 */
    private void removeFile(RoleId owner) {
        Path file = file(owner);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new DomainError("note.delete.failed", "笔记文件删除失败: " + file, e);
        }
    }

    // ── 内部：Note ⇄ Map ────────────────────────────────────────────

    private Map<String, Object> toMap(Note note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", note.id().value());
        m.put("owner", note.owner().value());
        m.put("title", note.title());
        m.put("body", Text.orEmpty(note.body()));
        if (note.remindDay() != null) {
            m.put("remind_day", note.remindDay());
        }
        if (note.remindTick() != null) {
            m.put("remind_tick", note.remindTick());
        }
        m.put("updated_at", note.updatedAt() == null ? 0.0 : note.updatedAt().toEpochMilli() / 1000.0);
        return m;
    }

    private Note fromMap(RoleId owner, Map<?, ?> map) {
        String title = str(map.get("title"));
        if (Text.isBlank(title)) {
            return null;
        }
        String rawId = str(map.get("id"));
        NoteId id = new NoteId(Text.isBlank(rawId) ? newId().value() : rawId);
        String rawOwner = str(map.get("owner"));
        RoleId noteOwner = Text.isBlank(rawOwner) ? owner : new RoleId(rawOwner);
        return new Note(id, noteOwner, title, Text.orEmpty(str(map.get("body"))),
                intOrNull(map.get("remind_day")), intOrNull(map.get("remind_tick")),
                instantOrNull(map.get("updated_at")));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && s.matches("-?\\d+")) {
            return Integer.parseInt(s.trim());
        }
        return null;
    }

    private static Instant instantOrNull(Object value) {
        if (value instanceof Number n) {
            return Instant.ofEpochMilli((long) (n.doubleValue() * 1000));
        }
        if (value instanceof String s && s.matches("-?\\d+(\\.\\d+)?")) {
            return Instant.ofEpochMilli((long) (Double.parseDouble(s.trim()) * 1000));
        }
        return null;
    }

    // ── 内部：小工具 ────────────────────────────────────────────────

    private static Optional<Note> find(List<Note> notes, String title) {
        for (Note note : notes) {
            if (title.equals(note.title())) {
                return Optional.of(note);
            }
        }
        return Optional.empty();
    }

    /** 同标题覆盖时保留原 id、刷新 updatedAt。 */
    private static Note refresh(Note incoming, Note existing) {
        NoteId id = existing != null ? existing.id()
                : (incoming.id() != null ? incoming.id() : newId());
        return new Note(id, incoming.owner(), incoming.title(), incoming.body(),
                incoming.remindDay(), incoming.remindTick(), Instant.now());
    }

    private static void upsert(List<Note> notes, Note note) {
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i).title().equals(note.title())) {
                notes.set(i, note);
                return;
            }
        }
        notes.add(note);
    }

    private static NoteId newId() {
        return new NoteId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    private static void requireOwner(RoleId owner) {
        if (owner == null) {
            throw new DomainError("note.owner.null", "笔记归属角色不能为空");
        }
    }

    private static void requireNote(Note note) {
        if (note == null) {
            throw new DomainError("note.null", "笔记不能为空");
        }
        requireOwner(note.owner());
        if (Text.isBlank(note.title())) {
            throw new DomainError("note.title.blank", "笔记标题不能为空");
        }
    }

    private ReentrantLock lock(RoleId owner) {
        return locks.computeIfAbsent(owner.value(), k -> new ReentrantLock());
    }
}
