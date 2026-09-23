package com.agent.software.tools.toolkits.note;

import com.agent.software.store.NoteStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** write_note：新建/覆盖一条笔记（同名覆盖）。 */
public class WriteNote extends Tool {

    private final NoteStore store;

    public WriteNote(NoteStore store) {
        this.store = store;
    }

    @Override
    public String getToolName() {
        return "write_note";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "Title of the note (used as the file name; writing an existing title overwrites it).");
        schema.put("content", "Full text content of the note (markdown is fine).");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Write one of your own notes. It is stored as a markdown file and survives across days "
                + "(your conversation context is cleared every night). Writing a title that already exists "
                + "overwrites that note; use edit_note to change an existing note.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (store == null) {
            return "write_note error: no note store";
        }
        String name = str(args.get("name"));
        String content = str(args.get("content"));
        if (name.isBlank()) {
            return "write_note error: needs a note name";
        }
        if (args.get("content") == null) {
            return "write_note error: needs note content";
        }
        boolean existed = store.exists(name);
        try {
            store.writeNote(name, content);
        } catch (Exception e) {
            return "write_note error: " + e.getMessage();
        }
        return "write_note: " + (existed ? "note overwritten" : "note written")
                + ": " + NoteStore.sanitizeTitle(name) + " (" + content.length() + " chars)";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
