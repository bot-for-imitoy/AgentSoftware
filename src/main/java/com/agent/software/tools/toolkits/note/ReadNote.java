package com.agent.software.tools.toolkits.note;

import com.agent.software.store.NoteStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * read_note — 读取指定标题的笔记内容.
 */
public class ReadNote extends Tool {

    private final NoteStore noteStore;

    public ReadNote(NoteStore noteStore) {
        super();
        this.noteStore = noteStore;
    }

    @Override
    public String getToolName() {
        return "read_note";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "The name of the note to read.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object oname = args.get("name");
        if (!(oname instanceof String)) {
            return oname == null
                    ? "read_note: Error: needs a note name"
                    : "read_note: Error: name is not a string";
        }
        String content = this.noteStore.readNote((String) oname);
        if (content == null) {
            return "read_note: Note not found: " + oname;
        }
        return content;
    }
}
