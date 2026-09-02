package com.agent.software.event;

import com.agent.software.core.Types;
import com.agent.software.store.NoteStore;
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
 * Unified note + task (note = content + optional reminder time) tests (the Java counterpart of the Python test_note_reminder.py).
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
        store.writeNote("Write weekly report", "This week's summary", 50, null);
        List<TimeEventBus.ScheduledTask> tasks = tm.listTasks("tester_1");
        assertEquals(1, tasks.size());
        assertEquals(50, tasks.get(0).targetTick);
        assertEquals("Write weekly report", tasks.get(0).payload.get("note_title"));
        assertTrue(tasks.get(0).description.contains("[Note Reminder]"));
        Map<String, Object> reminder = store.getReminder("Write weekly report");
        assertEquals(1, ((Number) reminder.get("day")).intValue());
        assertEquals(50, ((Number) reminder.get("tick")).intValue());
    }

    @Test
    void testWriteNoteWithoutReminder() {
        TimeEventBus tm = new TimeEventBus();
        tm.checkInterval = 0.05;
        NoteStore store = new NoteStore(tmp.toString(), "tester_1", tm);
        store.writeNote("Plain note", "No reminder", null, null);
        assertTrue(tm.listTasks("tester_1").isEmpty());
        assertNull(store.getReminder("Plain note"));
    }

    @Test
    void testDeleteNoteCancelsReminder() {
        TimeEventBus tm = new TimeEventBus();
        tm.checkInterval = 0.05;
        NoteStore store = new NoteStore(tmp.toString(), "tester_1", tm);
        store.writeNote("Todo", "Delete it", 10, null);
        assertEquals(1, tm.listTasks("tester_1").size());
        assertTrue(store.deleteNote("Todo"));
        assertTrue(tm.listTasks("tester_1").isEmpty());
    }

    @Test
    void testEditNoteResetsReminder() {
        TimeEventBus tm = new TimeEventBus();
        tm.checkInterval = 0.05;
        NoteStore store = new NoteStore(tmp.toString(), "tester_1", tm);
        store.writeNote("Plan", "v1", 5, null);
        store.editNote("Plan", "v2", 30, null);
        List<TimeEventBus.ScheduledTask> tasks = tm.listTasks("tester_1");
        assertEquals(1, tasks.size());  // old reminder cancelled, no duplicate
        assertEquals(30, tasks.get(0).targetTick);
        store.editNote("Plan", "v3", null, null);  // without remind_tick → keeps the original reminder
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
        store.writeNote("Afternoon meeting", "Remember to prepare materials", 3, null);
        tm.start();
        try {
            tm.debugSetTick(3);  // fast-forward to Tick 3 (reminder due)
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline
                    && captured.stream().noneMatch(e -> "TASK_DUE".equals(e.eventType))) {
                sleep(50);
            }
            List<Types.Event> due = captured.stream()
                    .filter(e -> "TASK_DUE".equals(e.eventType)).toList();
            assertEquals(1, due.size());
            assertEquals("tester_1", due.get(0).targetRole);
            assertEquals("Afternoon meeting", due.get(0).payload.get("note_title"));
        } finally {
            tm.stop();
        }
    }
}
