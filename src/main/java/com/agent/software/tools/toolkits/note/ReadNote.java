package com.agent.software.tools.toolkits.note;

import com.agent.software.store.NoteStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** read_note：读一条笔记的全文。 */
public class ReadNote extends Tool {

    private final NoteStore store;

    public ReadNote(NoteStore store) {
        this.store = store;
    }

    @Override
    public String getToolName() {
        return "read_note";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "Title of the note to read (see list_notes).");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Read the full content of one of your own notes.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (store == null) {
            return "read_note error: no note store";
        }
        String name = args.get("name") == null ? "" : String.valueOf(args.get("name"));
        if (name.isBlank()) {
            return "read_note error: needs a note name";
        }
        String content;
        try {
            content = store.readNote(name);
        } catch (Exception e) {
            return "read_note error: " + e.getMessage();
        }
        if (content == null) {
            return "read_note error: note not found: " + NoteStore.sanitizeTitle(name)
                    + " (use list_notes to see your notes)";
        }
        return content;
    }
}
