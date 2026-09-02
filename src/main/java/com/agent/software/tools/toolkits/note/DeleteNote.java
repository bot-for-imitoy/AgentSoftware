package com.agent.software.tools.toolkits.note;

import com.agent.software.store.NoteStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * delete_note - delete a note (also cancels its associated reminder).
 */
public class DeleteNote extends Tool {

    private final NoteStore noteStore;

    public DeleteNote(NoteStore noteStore) {
        super();
        this.noteStore = noteStore;
    }

    @Override
    public String getToolName() {
        return "delete_note";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "The name of the note to delete.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object oname = args.get("name");
        if (!(oname instanceof String)) {
            return oname == null
                    ? "delete_note: Error: needs a note name"
                    : "delete_note: Error: name is not a string";
        }
        if (this.noteStore.deleteNote((String) oname)) {
            return "delete_note: Note deleted: " + oname;
        }
        return "delete_note: Note not found: " + oname;
    }
}
