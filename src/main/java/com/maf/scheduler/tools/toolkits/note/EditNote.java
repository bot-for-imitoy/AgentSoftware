package com.maf.scheduler.tools.toolkits.note;

import com.maf.scheduler.store.NoteStore;
import com.maf.scheduler.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * edit_note — 编辑已有笔记 (覆盖原内容), 不存在则自动创建.
 * 提供 reminder_tick 可重置提醒时间; 不提供则保留原提醒.
 */
public class EditNote extends Tool {

    private final NoteStore noteStore;

    public EditNote(NoteStore noteStore) {
        super();
        this.noteStore = noteStore;
    }

    @Override
    public String getToolName() {
        return "edit_note";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "The name of the note to edit.");
        schema.put("content", "The new content of the note.");
        schema.put("reminder_tick", "(Optional) Reminder Tick. 0~60.");
        schema.put("reminder_day", "(Optional, needs reminder_tick) Reminder day, default to today.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object oname = args.get("name");
        Object ocontent = args.get("content");
        Object oreminderTick = args.get("reminder_tick");
        Object oreminderDay = args.get("reminder_day");
        if (!(oname instanceof String)) {
            return oname == null
                    ? "edit_note: Error: needs a note name"
                    : "edit_note: Error: name is not a string";
        }
        if (!(ocontent instanceof String)) {
            return ocontent == null
                    ? "edit_note: Error: needs note content"
                    : "edit_note: Error: content is not a string";
        }
        Integer reminderTick = toInt(oreminderTick);
        if (oreminderTick != null && reminderTick == null) {
            return "edit_note: Error: reminder_tick is not an integer";
        }
        Integer reminderDay = toInt(oreminderDay);
        if (oreminderDay != null && reminderDay == null) {
            return "edit_note: Error: reminder_day is not an integer";
        }
        String name = (String) oname;
        String content = (String) ocontent;
        try {
            this.noteStore.editNote(name, content, reminderTick, reminderDay);
        } catch (IllegalArgumentException exc) {
            return "edit_note: Error: " + exc.getMessage();
        }
        if (reminderTick != null) {
            Map<String, Object> r = this.noteStore.getReminder(name);
            int day = r != null ? ((Number) r.get("day")).intValue()
                    : (reminderDay != null ? reminderDay : 1);
            int tick = r != null ? ((Number) r.get("tick")).intValue() : reminderTick;
            return "edit_note: Note updated, reminder reset: " + name
                    + " (will remind you at day " + day + " tick " + tick + ")";
        }
        return "edit_note: Note updated: " + name;
    }

    /** 兼容 Integer / Number / 数字字符串. 无法解析返回 null. */
    private static Integer toInt(Object o) {
        if (o instanceof Integer) {
            return (Integer) o;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && s.matches("-?\\d+")) {
            return Integer.parseInt(s);
        }
        return null;
    }
}
