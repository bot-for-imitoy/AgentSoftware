package com.agent.software.core;

import com.agent.software.store.NoteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NoteStore unit tests (the Java counterpart of the Python test_note_store.py).
 */
class NoteStoreTest {

    @TempDir
    Path tmp;

    // ── Title sanitization (High-3 injection surface) ──────────

    @Test
    void testShellMetacharsStripped() {
        String clean = NoteStore.sanitizeTitle("it's my note $(whoami) `id`");
        for (char ch : new char[]{'\'', '`', '$', ';', '&'}) {
            assertFalse(clean.indexOf(ch) >= 0);
        }
        assertFalse(clean.contains("$("));
        assertEquals("it_s_my_note_(whoami)_id_", clean);
    }

    @Test
    void testIllegalFilenameCharsStripped() {
        assertEquals("a_b_c_d_e_f_g_h_i_j", NoteStore.sanitizeTitle("a/b\\c:d*e?f\"g<h>i|j"));
        assertEquals("untitled", NoteStore.sanitizeTitle("   "));
    }

    @Test
    void testNormalTitleKept() {
        assertEquals("weekly-report-2026-08", NoteStore.sanitizeTitle("weekly-report-2026-08"));
    }

    // ── Summaries sorted by numeric day ───────────────────────

    @Test
    void testNumericOrdering() {
        NoteStore store = new NoteStore(tmp.toString(), "CEO", null);
        store.saveSummary("Day 9 summary", 9);
        store.saveSummary("Day 10 summary", 10);
        assertEquals("Day 10 summary", store.getLatestSummary(11));
        assertEquals("Day 9 summary", store.getLatestSummary(10));
        assertNull(store.getLatestSummary(9));
    }

    @Test
    void testNoSummaries() {
        NoteStore store = new NoteStore(tmp.toString(), "CEO", null);
        assertNull(store.getLatestSummary(null));
    }

    // ── delete_note actually deletes the file ─────────────────

    @Test
    void testLocalDeleteRemovesFile() throws IOException {
        NoteStore store = new NoteStore(tmp.toString(), "CEO", null);
        store.writeNote("Memo", "Content", null, null);
        Path path = tmp.resolve("CEO").resolve("notes").resolve("Memo.md");
        assertTrue(Files.exists(path));
        assertTrue(store.deleteNote("Memo"));
        assertFalse(Files.exists(path));
        assertFalse(store.deleteNote("Memo"));
    }

    // ── Write / read / list ───────────────────────────────────

    @Test
    void testWriteReadList() {
        NoteStore store = new NoteStore(tmp.toString(), "CEO", null);
        store.writeNote("Weekly report", "This week's summary", null, null);
        store.writeNote("Plan", "Next week's plan", null, null);
        List<String> titles = store.listNotes();
        // listNotes sorts by file name lexically: "Plan" < "Weekly report" → Plan first
        assertEquals(List.of("Plan", "Weekly_report"), titles);
        assertEquals("This week's summary", store.readNote("Weekly report"));
        assertNull(store.readNote("nonexistent"));
    }
}
