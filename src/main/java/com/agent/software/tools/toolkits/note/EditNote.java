package com.agent.software.tools.toolkits.note;

import com.agent.software.store.NoteStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** edit_note：改写一条已有笔记（不存在时按新建处理，内容整体覆盖）。 */
public class EditNote extends Tool {

    private final NoteStore store;

    public EditNote(NoteStore store) {
        this.store = store;
    }

    @Override
    public String getToolName() {
        return "edit_note";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "Title of the note to edit.");
        schema.put("content", "The new full content of the note (it replaces the old content).");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Edit one of your own notes. The content replaces the whole note, so read it first "
                + "(read_note) if you only want to change part of it. If the note does not exist it is created.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (store == null) {
            return "edit_note error: no note store";
        }
        String name = args.get("name") == null ? "" : String.valueOf(args.get("name"));
        if (name.isBlank()) {
            return "edit_note error: needs a note name";
        }
        if (args.get("content") == null) {
            return "edit_note error: needs note content";
        }
        String content = String.valueOf(args.get("content"));
        boolean existed;
        try {
            existed = store.editNote(name, content);
        } catch (Exception e) {
            return "edit_note error: " + e.getMessage();
        }
        return "edit_note: " + (existed ? "note updated" : "note created (it did not exist before)")
                + ": " + NoteStore.sanitizeTitle(name) + " (" + content.length() + " chars)";
    }
}
