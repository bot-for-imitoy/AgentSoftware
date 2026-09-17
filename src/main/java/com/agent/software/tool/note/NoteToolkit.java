package com.agent.software.tool.note;

import com.agent.software.kernel.DayTick;
import com.agent.software.kernel.Ids.NoteId;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.ScheduleId;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;
import com.agent.software.sim.clock.ReminderScheduler;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 笔记工具包（id {@code "note"}），暴露工具：write_note / edit_note / list_notes / read_note / delete_note。
 *
 * <p>提醒的注册/取消依赖一个<b>进程内</b>映射 {@code owner|title → ScheduleId}：
 * {@link Note} record 没有 scheduleId 字段（有意不改），而 {@link ReminderScheduler#cancel}
 * 需要 ScheduleId，因此只能在工具层记住。局限：进程重启后映射丢失，重启前注册、尚未触发的
 * 提醒会继续留在调度表里但无法再取消；重新 write/edit 会注册一条新提醒（旧的可能变成孤儿）。
 * 若要彻底解决，应在 {@link Note} 上加持久化字段，属于跨契约改动。
 */
public final class NoteToolkit implements Toolkit {

    private static final Logger log = LoggerFactory.getLogger(NoteToolkit.class);

    private final NoteBook notes;
    private final ReminderScheduler reminders;

    /** key = owner + "|" + title（见类 Javadoc 的局限说明）。 */
    private final Map<String, ScheduleId> schedules = new ConcurrentHashMap<>();

    public NoteToolkit(NoteBook notes, ReminderScheduler reminders) {
        this.notes = notes;
        this.reminders = reminders;
    }

    @Override
    public String id() {
        return "note";
    }

    @Override
    public List<Tool> instantiate() {
        return List.of(new WriteNote(), new EditNote(), new ListNotes(), new ReadNote(), new DeleteNote());
    }

    // ── write_note ─────────────────────────────────────────────────

    private final class WriteNote implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("write_note",
                    "写一条笔记（同名笔记会被整篇覆盖）。可选带上 remind_day/remind_tick 注册一条定时提醒。",
                    JsonSchema.object()
                            .string("title", "笔记标题，也是笔记的主键（同名覆盖）")
                            .string("content", "笔记正文")
                            .integer("remind_day", "（可选）提醒的天数；给了它才会注册提醒")
                            .integer("remind_tick", "（可选）提醒时刻，0 = 当天 08:00，默认 0；需与 remind_day 同时给出")
                            .required("title", "content"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String title = arg(arguments, "title");
            String content = arg(arguments, "content");
            if (Text.isBlank(title)) {
                return ToolResult.error("write_note: 缺少笔记标题（title）");
            }
            if (Text.isBlank(content)) {
                return ToolResult.error("write_note: 缺少笔记正文（content）");
            }
            OptionalInt day = arguments.intValue("remind_day");
            OptionalInt tick = arguments.intValue("remind_tick");
            if (day.isEmpty() && tick.isPresent()) {
                return ToolResult.error("write_note: remind_tick 必须与 remind_day 一起使用");
            }
            try {
                boolean remind = day.isPresent();
                notes.write(new Note(newNoteId(), agent, title, content,
                        remind ? day.getAsInt() : null,
                        remind ? tick.orElse(0) : null,
                        Instant.now()));
                if (remind) {
                    int atTick = tick.orElse(0);
                    registerReminder(agent, title, content, day.getAsInt(), atTick);
                    return ToolResult.ok("write_note: 已写入笔记并注册提醒：" + title
                            + "（第 " + day.getAsInt() + " 天 tick " + atTick + "）");
                }
                // 同名覆盖且本次没给提醒：笔记里的 remindDay 已被清空，旧的调度条目若还挂着
                // 就变成"幽灵提醒"，这里一并取消，保持记录与调度一致（契约未明说，主动对齐语义）。
                if (cancelReminder(agent, title)) {
                    return ToolResult.ok("write_note: 已覆盖笔记，并取消了原有提醒：" + title);
                }
                return ToolResult.ok("write_note: 已写入笔记：" + title);
            } catch (RuntimeException e) {
                return ToolResult.error("write_note: 写入失败: " + e.getMessage());
            }
        }
    }

    // ── edit_note ──────────────────────────────────────────────────

    private final class EditNote implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("edit_note",
                    "修改已有笔记的正文/提醒时间（不存在则新建）。原来有提醒时先取消旧提醒再按新参数注册。",
                    JsonSchema.object()
                            .string("title", "要修改的笔记标题（不存在则新建）")
                            .string("content", "新的笔记正文")
                            .integer("remind_day", "（可选）新的提醒天数；不给出则沿用原提醒")
                            .integer("remind_tick", "（可选）新的提醒时刻，0 = 当天 08:00，默认 0")
                            .required("title", "content"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String title = arg(arguments, "title");
            String content = arg(arguments, "content");
            if (Text.isBlank(title)) {
                return ToolResult.error("edit_note: 缺少笔记标题（title）");
            }
            if (Text.isBlank(content)) {
                return ToolResult.error("edit_note: 缺少笔记正文（content）");
            }
            OptionalInt day = arguments.intValue("remind_day");
            OptionalInt tick = arguments.intValue("remind_tick");
            if (day.isEmpty() && tick.isPresent()) {
                return ToolResult.error("edit_note: remind_tick 必须与 remind_day 一起使用");
            }
            try {
                Optional<Note> existing = notes.read(agent, title);
                // 未显式给新时间时沿用原提醒（对应 master 的"省略即保持"）。
                // 注意：必须显式装箱，否则 `int : Integer` 的条件表达式会被数值提升成 int，
                // 在没有旧 remindDay 时对 null 拆箱抛 NPE（edit_note 永远失败）。
                Integer newDay = day.isPresent() ? Integer.valueOf(day.getAsInt())
                        : existing.map(Note::remindDay).orElse(null);
                Integer newTick = newDay == null ? null
                        : Integer.valueOf(day.isPresent() ? tick.orElse(0)
                        : existing.map(Note::remindTick).orElse(0));
                NoteId id = existing.map(Note::id).orElseGet(NoteToolkit::newNoteId);
                notes.edit(new Note(id, agent, title, content, newDay, newTick, Instant.now()));
                if (newDay != null) {
                    registerReminder(agent, title, content, newDay, newTick == null ? 0 : newTick);
                    return ToolResult.ok("edit_note: 已更新笔记并重置提醒：" + title
                            + "（第 " + newDay + " 天 tick " + (newTick == null ? 0 : newTick) + "）");
                }
                return ToolResult.ok("edit_note: 已更新笔记：" + title);
            } catch (RuntimeException e) {
                return ToolResult.error("edit_note: 更新失败: " + e.getMessage());
            }
        }
    }

    // ── list_notes ─────────────────────────────────────────────────

    private final class ListNotes implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("list_notes", "列出当前所有笔记的标题（每日总结不在此列出）。", JsonSchema.object());
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            List<Note> normal = new ArrayList<>();
            for (Note note : notes.list(agent)) {
                if (!note.isSummary()) {
                    normal.add(note);
                }
            }
            if (normal.isEmpty()) {
                return ToolResult.ok("list_notes: 当前没有笔记。");
            }
            StringBuilder sb = new StringBuilder("list_notes: 共 " + normal.size() + " 条笔记：");
            for (Note note : normal) {
                sb.append("\n- ").append(note.title());
                if (note.remindDay() != null) {
                    sb.append("（提醒：第 ").append(note.remindDay())
                            .append(" 天 tick ").append(note.remindTick() == null ? 0 : note.remindTick()).append("）");
                }
            }
            return ToolResult.ok(sb.toString());
        }
    }

    // ── read_note ──────────────────────────────────────────────────

    private final class ReadNote implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("read_note", "读取指定标题笔记的正文。",
                    JsonSchema.object()
                            .string("title", "要读取的笔记标题")
                            .required("title"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String title = arg(arguments, "title");
            if (Text.isBlank(title)) {
                return ToolResult.error("read_note: 缺少笔记标题（title）");
            }
            try {
                return notes.read(agent, title)
                        .<ToolResult>map(note -> ToolResult.ok(Text.orEmpty(note.body())))
                        .orElseGet(() -> ToolResult.error("read_note: 笔记不存在：" + title));
            } catch (RuntimeException e) {
                return ToolResult.error("read_note: 读取失败: " + e.getMessage());
            }
        }
    }

    // ── delete_note ────────────────────────────────────────────────

    private final class DeleteNote implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("delete_note", "删除一条笔记，并取消它已注册的提醒。",
                    JsonSchema.object()
                            .string("title", "要删除的笔记标题")
                            .required("title"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String title = arg(arguments, "title");
            if (Text.isBlank(title)) {
                return ToolResult.error("delete_note: 缺少笔记标题（title）");
            }
            try {
                if (!notes.delete(agent, title)) {
                    return ToolResult.error("delete_note: 笔记不存在：" + title);
                }
                cancelReminder(agent, title);
                return ToolResult.ok("delete_note: 已删除笔记：" + title);
            } catch (RuntimeException e) {
                return ToolResult.error("delete_note: 删除失败: " + e.getMessage());
            }
        }
    }

    // ── 内部：提醒映射 ──────────────────────────────────────────────

    /** 注册提醒；同一 owner|title 的旧提醒先取消，避免重复。 */
    private void registerReminder(RoleId owner, String title, String content, int day, int tick) {
        cancelReminder(owner, title);
        ScheduleId id = reminders.schedule("[笔记提醒] " + title, owner, new DayTick(day, tick),
                Payload.of("title", title).with("text", content));
        schedules.put(key(owner, title), id);
        log.info("[{}] 笔记提醒已注册: {}（第 {} 天 tick {}）", owner.value(), title, day, tick);
    }

    /** 取消内存映射里记录的提醒；没有记录时返回 false（见类 Javadoc 的重启局限）。 */
    private boolean cancelReminder(RoleId owner, String title) {
        ScheduleId id = schedules.remove(key(owner, title));
        return id != null && reminders.cancel(id);
    }

    // ── 内部：小工具 ────────────────────────────────────────────────

    private static String key(RoleId owner, String title) {
        return owner.value() + "|" + title;
    }

    private static String arg(Payload arguments, String name) {
        return Text.orEmpty(arguments.stringOr(name, "")).strip();
    }

    private static NoteId newNoteId() {
        return new NoteId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }
}
