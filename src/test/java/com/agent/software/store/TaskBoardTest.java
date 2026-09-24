package com.agent.software.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务看板：按组存放任务记录、切换基线、完成状态实时回写。 */
class TaskBoardTest {

    @Test
    void groupsAreIsolatedAndSwitchingMovesTheBaseline(@TempDir Path dir) {
        TaskBoard board = new TaskBoard(dir, "COO");
        assertEquals(List.of("default"), board.groups());
        assertEquals("default", board.currentGroup());

        board.add("id-1", "baseline task", "", "architect", "day 1 09:00 (tick 3600)", "");
        assertEquals(1, board.count(""));
        assertTrue(board.switchGroup("release"), "不存在的组应被创建");
        assertEquals("release", board.currentGroup());
        assertEquals(0, board.count(""));
        board.add("id-2", "release task", "", "release_manager", "day 2 09:00", "");
        assertEquals(1, board.count("default"));
        assertEquals(1, board.count("release"));

        // 显式指定组时不看基线
        board.add("id-3", "extra default", "", "CTO", "day 3 09:00", "default");
        assertEquals(2, board.count("default"));

        assertFalse(board.switchGroup("default"), "已有组不算新建");
        assertEquals("baseline task", board.items("").get(0).title);

        // 换实例复核
        TaskBoard reRead = new TaskBoard(dir, "COO");
        assertEquals("default", reRead.currentGroup());
        assertEquals(List.of("default", "release"), reRead.groups());
        assertEquals(2, reRead.count("default"));
        assertEquals(1, reRead.count("release"));
    }

    @Test
    void taskCompletionIsWrittenBackAndPersisted(@TempDir Path dir) {
        TaskBoard board = new TaskBoard(dir, "COO");
        board.add("uuid-a", "ship it", "", "backend_lead", "day 1 10:00", "release");

        assertTrue(board.recordStatus("uuid-a", "done", 1234));
        TaskBoard reRead = new TaskBoard(dir, "COO");
        TaskBoard.Record r = reRead.find("release", "uuid-a");
        assertNotNull(r);
        assertEquals("done", r.status);
        assertEquals(1234, r.tokens);

        // 不在看板上的任务（系统派活）不该凭空建记录，也不该报错
        assertFalse(board.recordStatus("uuid-unknown", "done", 1));
        assertEquals(1, reRead.count("release"));
    }

    @Test
    void findUpdateAndRemove(@TempDir Path dir) {
        TaskBoard board = new TaskBoard(dir, "COO");
        board.add("abcdef123456", "original", "", "CTO", "day 1", "");
        assertNotNull(board.find("", "abcdef"), "唯一前缀应能定位");
        assertNull(board.find("", "zzz"));

        assertTrue(board.update("abcdef", "renamed", "with detail", "architect", "day 2"));
        TaskBoard.Record r = board.findAnywhere("abcdef");
        assertEquals("renamed", r.title);
        assertEquals("with detail", r.detail);
        assertEquals("architect", r.target);
        assertEquals("day 2", r.due);

        assertTrue(board.remove("abcdef"));
        assertFalse(board.remove("abcdef"));
        assertEquals(0, board.count("default"));
    }
}
