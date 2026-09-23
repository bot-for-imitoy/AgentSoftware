package com.agent.software.store;

import com.agent.software.tools.Tool;
import com.agent.software.tools.toolkits.note.Note;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 笔记库（NoteStore）与 note 工具包的增删改查。 */
class NoteStoreAndToolsTest {

    @Test
    void writeReadEditDeleteList(@TempDir Path dir) {
        NoteStore store = new NoteStore(dir, "CEO");
        assertTrue(store.listNotes().isEmpty());
        assertNull(store.readNote("计划"));
        assertFalse(store.deleteNote("计划"), "不存在时删除应返回 false");

        store.writeNote("计划", "第一版");
        assertTrue(store.exists("计划"));
        assertEquals("第一版", store.readNote("计划"));
        assertEquals(List.of("计划"), store.listNotes());
        assertTrue(Files.exists(dir.resolve("CEO/notes/计划.md")));

        assertTrue(store.editNote("计划", "第二版"), "已存在时应报告是覆盖");
        assertEquals("第二版", store.readNote("计划"));
        assertFalse(store.editNote("另一条", "新建"), "不存在时 edit 应按新建处理");

        assertTrue(store.deleteNote("计划"));
        assertFalse(store.exists("计划"));
    }

    @Test
    void storesAreIsolatedPerRole(@TempDir Path dir) {
        new NoteStore(dir, "CEO").writeNote("secret", "ceo only");
        NoteStore cto = new NoteStore(dir, "CTO");
        assertTrue(cto.listNotes().isEmpty());
        assertNull(cto.readNote("secret"));
        assertEquals("ceo only", new NoteStore(dir, "CEO").readNote("secret"));
    }

    @Test
    void titleIsSanitizedIntoAFileName(@TempDir Path dir) {
        assertEquals("a_b", NoteStore.sanitizeTitle(" a/b "));
        assertEquals("计划", NoteStore.sanitizeTitle("计划.md"));
        assertEquals("untitled", NoteStore.sanitizeTitle("   "));
        NoteStore store = new NoteStore(dir, "CEO");
        store.writeNote("../../etc/passwd", "x");
        assertEquals(List.of("etc_passwd"), store.listNotes(),
                "路径分隔符和前缀点不能被当成目录");
        assertTrue(Files.exists(dir.resolve("CEO/notes/etc_passwd.md")));
    }

    @Test
    void noteToolkitDoesCrudThroughTools(@TempDir Path dir) {
        Note note = new Note(new NoteStore(dir, "CEO"));
        List<String> names = note.getTools().stream().map(Tool::getToolName).sorted().toList();
        assertEquals(List.of("delete_note", "edit_note", "list_notes", "read_note", "write_note"), names);

        assertTrue(note.trigger("list_notes", Map.of()).contains("no notes yet"));

        String written = note.trigger("write_note", Map.of("name", "weekly", "content", "hello"));
        assertTrue(written.contains("note written"), written);
        assertEquals("hello", note.trigger("read_note", Map.of("name", "weekly")));

        String edited = note.trigger("edit_note", Map.of("name", "weekly", "content", "hello again"));
        assertTrue(edited.contains("note updated"), edited);
        assertEquals("hello again", note.trigger("read_note", Map.of("name", "weekly")));

        assertTrue(note.trigger("list_notes", Map.of()).contains("weekly"));
        assertTrue(note.trigger("delete_note", Map.of("name", "weekly")).contains("deleted"));
        assertTrue(note.trigger("list_notes", Map.of()).contains("no notes yet"));
    }

    @Test
    void noteToolsValidateTheirArguments(@TempDir Path dir) {
        Note note = new Note(new NoteStore(dir, "CEO"));
        assertTrue(note.trigger("write_note", Map.of("content", "x")).contains("needs a note name"));
        assertTrue(note.trigger("write_note", Map.of("name", "x")).contains("needs note content"));
        assertTrue(note.trigger("read_note", Map.of("name", "nope")).contains("not found"));
        assertTrue(note.trigger("delete_note", Map.of("name", "nope")).contains("not found"));
        assertTrue(note.trigger("edit_note", Map.of("name", "x")).contains("needs note content"));
        assertTrue(note.trigger("read_note", Map.of()).contains("needs a note name"));
    }
}
