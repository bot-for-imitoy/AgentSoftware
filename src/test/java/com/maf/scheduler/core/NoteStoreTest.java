package com.maf.scheduler.core;

import com.maf.scheduler.store.NoteStore;
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
 * NoteStore 单元测试 (Python 版 test_note_store.py 的 Java 对应物).
 */
class NoteStoreTest {

    @TempDir
    Path tmp;

    // ── 标题清洗 (High-3 注入面) ─────────────────────────

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
        assertEquals("周报-2026-08", NoteStore.sanitizeTitle("周报-2026-08"));
    }

    // ── 总结按天数值排序 ─────────────────────────────────

    @Test
    void testNumericOrdering() {
        NoteStore store = new NoteStore(tmp.toString(), "CEO", null);
        store.saveSummary("第九天总结", 9);
        store.saveSummary("第十天总结", 10);
        assertEquals("第十天总结", store.getLatestSummary(11));
        assertEquals("第九天总结", store.getLatestSummary(10));
        assertNull(store.getLatestSummary(9));
    }

    @Test
    void testNoSummaries() {
        NoteStore store = new NoteStore(tmp.toString(), "CEO", null);
        assertNull(store.getLatestSummary(null));
    }

    // ── delete_note 真实删除文件 ─────────────────────────

    @Test
    void testLocalDeleteRemovesFile() throws IOException {
        NoteStore store = new NoteStore(tmp.toString(), "CEO", null);
        store.writeNote("备忘录", "内容", null, null);
        Path path = tmp.resolve("CEO").resolve("notes").resolve("备忘录.md");
        assertTrue(Files.exists(path));
        assertTrue(store.deleteNote("备忘录"));
        assertFalse(Files.exists(path));
        assertFalse(store.deleteNote("备忘录"));
    }

    // ── 读写列表 ─────────────────────────────────────────

    @Test
    void testWriteReadList() {
        NoteStore store = new NoteStore(tmp.toString(), "CEO", null);
        store.writeNote("周报", "本周总结", null, null);
        store.writeNote("计划", "下周计划", null, null);
        List<String> titles = store.listNotes();
        // Python sorted() 按 Unicode 码点排序: 周(0x5468) < 计(0x8BA1) → 周报在前
        assertEquals(List.of("周报", "计划"), titles);
        assertEquals("本周总结", store.readNote("周报"));
        assertNull(store.readNote("不存在"));
    }
}
