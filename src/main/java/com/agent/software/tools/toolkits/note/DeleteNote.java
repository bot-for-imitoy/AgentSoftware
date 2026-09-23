package com.agent.software.tools.toolkits.note;

import com.agent.software.store.NoteStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** delete_note：删除一条笔记（不可恢复）。 */
public class DeleteNote extends Tool {

    private final NoteStore store;

    public DeleteNote(NoteStore store) {
        this.store = store;
    }

    @Override
    public String getToolName() {
        return "delete_note";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "Title of the note to delete.");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Delete one of your own notes. This cannot be undone.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (store == null) {
            return "delete_note error: no note store";
        }
        String name = args.get("name") == null ? "" : String.valueOf(args.get("name"));
        if (name.isBlank()) {
            return "delete_note error: needs a note name";
        }
        boolean removed;
        try {
            removed = store.deleteNote(name);
        } catch (Exception e) {
            return "delete_note error: " + e.getMessage();
        }
        if (!removed) {
            return "delete_note error: note not found: " + NoteStore.sanitizeTitle(name)
                    + " (use list_notes to see your notes)";
        }
        return "delete_note: note deleted: " + NoteStore.sanitizeTitle(name);
    }
}
