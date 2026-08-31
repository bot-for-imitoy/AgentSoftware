package com.maf.scheduler.tools.toolkits.note;

import com.maf.scheduler.store.NoteStore;
import com.maf.scheduler.tools.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * list_notes — 列出当前所有笔记标题 (含提醒时间信息, 若有).
 */
public class ListNotes extends Tool {

    private final NoteStore noteStore;

    public ListNotes(NoteStore noteStore) {
        super();
        this.noteStore = noteStore;
    }

    @Override
    public String getToolName() {
        return "list_notes";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String handler(Map<String, Object> args) {
        List<String> titles = this.noteStore.listNotes();
        if (titles.isEmpty()) {
            return "list_notes: (no notes yet)";
        }
        StringBuilder sb = new StringBuilder();
        for (String t : titles) {
            Map<String, Object> r = this.noteStore.getReminder(t);
            if (r != null) {
                sb.append("- ").append(t).append(" (reminder: day ")
                        .append(r.get("day")).append(" tick ").append(r.get("tick")).append(")\n");
            } else {
                sb.append("- ").append(t).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }
}
