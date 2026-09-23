package com.agent.software.tools.toolkits.note;

import com.agent.software.store.NoteStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** list_notes：列出自己所有笔记的标题与字数。 */
public class ListNotes extends Tool {

    private final NoteStore store;

    public ListNotes(NoteStore store) {
        this.store = store;
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
    public String getDescription() {
        return "List the titles of all your notes (with size), so you know what you have written down.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (store == null) {
            return "list_notes error: no note store";
        }
        List<String> titles;
        try {
            titles = store.listNotes();
        } catch (Exception e) {
            return "list_notes error: " + e.getMessage();
        }
        if (titles.isEmpty()) {
            return "list_notes: (no notes yet)";
        }
        StringBuilder sb = new StringBuilder("list_notes: " + titles.size() + " note(s)\n");
        for (String t : titles) {
            String content = "";
            try {
                content = store.readNote(t);
            } catch (Exception ignored) {
                // 读失败不影响列表
            }
            sb.append("- ").append(t).append(" (").append(content == null ? 0 : content.length())
                    .append(" chars)\n");
        }
        return sb.toString().stripTrailing();
    }
}
