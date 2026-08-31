package com.maf.scheduler.core;

import com.maf.scheduler.event.TimeEventBus;
import com.maf.scheduler.store.NoteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 笔记与任务统一 (笔记 = 内容 + 可选提醒时间) 测试 (Python 版 test_note_reminder.py).
 */
class NoteReminderTest {

    @TempDir
    Path tmp;

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testWriteNoteWithReminder() {
        TimeEventBus tm = new TimeEventBus();
        tm.checkInterval = 0.05;
        NoteStore store = new NoteStore(tmp.toString(), "tester_1", tm);
        store.writeNote("写周报", "本周工作小结", 50, null);
        List<TimeEventBus.ScheduledTask> tasks = tm.listTasks("tester_1");
        assertEquals(1, tasks.size());
        assertEquals(50, tasks.get(0).targetTick);
        assertEquals("写周报", tasks.get(0).payload.get("note_title"));
        assertTrue(tasks.get(0).description.contains("[笔记提醒]"));
        Map<String, Object> reminder = store.getReminder("写周报");
        assertEquals(1, ((Number) reminder.get("day")).intValue());
        assertEquals(50, ((Number) reminder.get("tick")).intValue());
    }

    @Test
    void testWriteNoteWithoutReminder() {
        TimeEventBus tm = new TimeEventBus();
        tm.checkInterval = 0.05;
        NoteStore store = new NoteStore(tmp.toString(), "tester_1", tm);
        store.writeNote("普通笔记", "没有提醒", null, null);
        assertTrue(tm.listTasks("tester_1").isEmpty());
        assertNull(store.getReminder("普通笔记"));
    }

    @Test
    void testDeleteNoteCancelsReminder() {
        TimeEventBus tm = new TimeEventBus();
        tm.checkInterval = 0.05;
        NoteStore store = new NoteStore(tmp.toString(), "tester_1", tm);
        store.writeNote("待办", "删掉", 10, null);
        assertEquals(1, tm.listTasks("tester_1").size());
        assertTrue(store.deleteNote("待办"));
        assertTrue(tm.listTasks("tester_1").isEmpty());
    }

    @Test
    void testEditNoteResetsReminder() {
        TimeEventBus tm = new TimeEventBus();
        tm.checkInterval = 0.05;
        NoteStore store = new NoteStore(tmp.toString(), "tester_1", tm);
        store.writeNote("计划", "v1", 5, null);
        store.editNote("计划", "v2", 30, null);
        List<TimeEventBus.ScheduledTask> tasks = tm.listTasks("tester_1");
        assertEquals(1, tasks.size());  // 旧提醒已取消, 不重复
        assertEquals(30, tasks.get(0).targetTick);
        store.editNote("计划", "v3", null, null);  // 不带 remind_tick → 保持原提醒
        tasks = tm.listTasks("tester_1");
        assertEquals(1, tasks.size());
        assertEquals(30, tasks.get(0).targetTick);
    }

    @Test
    void testReminderFiresTaskDueEvent() {
        TimeEventBus tm = new TimeEventBus();
        tm.checkInterval = 0.05;
        List<Types.Event> captured = new ArrayList<>();
        tm.setEventSender(captured::add);
        NoteStore store = new NoteStore(tmp.toString(), "tester_1", tm);
        store.writeNote("下午开会", "记得准备材料", 3, null);
        tm.start();
        try {
            tm.debugSetTick(3);  // 快进跳到 Tick 3 (提醒到期)
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline
                    && captured.stream().noneMatch(e -> "TASK_DUE".equals(e.eventType))) {
                sleep(50);
            }
            List<Types.Event> due = captured.stream()
                    .filter(e -> "TASK_DUE".equals(e.eventType)).toList();
            assertEquals(1, due.size());
            assertEquals("tester_1", due.get(0).targetRole);
            assertEquals("下午开会", due.get(0).payload.get("note_title"));
        } finally {
            tm.stop();
        }
    }
}
