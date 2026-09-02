package com.agent.software.event;

import com.agent.software.core.Types;
import com.agent.software.event.TimeEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TimeEventBus core time logic tests (the Java counterpart of the Python test_time_manager.py).
 */
class TimeManagerTest {

    private static final int TICKS_PER_DAY = 144;
    private static final int SHIFT_END_TICK = 60;

    private final List<TimeEventBus> buses = new ArrayList<>();

    private TimeEventBus makeBus() {
        TimeEventBus bus = new TimeEventBus();
        bus.checkInterval = 0.05;
        buses.add(bus);
        return bus;
    }

    @AfterEach
    void stopAll() {
        for (TimeEventBus b : buses) {
            try {
                b.stop();
            } catch (Exception ignored) {
            }
        }
        buses.clear();
    }

    private void jump(TimeEventBus bus, int targetTick) {
        bus.debugSetTick(targetTick);
        sleep(120);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Tick math (explicit state, independent of the clock) ─────

    @Test
    void testTickZeroAtStart() {
        TimeEventBus bus = makeBus();
        bus.start();
        bus.stop();
        assertEquals(0, bus.currentTick());
        assertEquals(1, bus.dayNumber());
        assertEquals(0, bus.tickOfDay());
    }

    @Test
    void testDerivedTickMath() {
        TimeEventBus bus = makeBus();
        bus.start();
        bus.stop();
        bus.debugSetTick(1);
        assertEquals(1, bus.currentTick());
        assertEquals(1, bus.dayNumber());
        bus.debugSetTick(144);
        assertEquals(144, bus.currentTick());
        assertEquals(2, bus.dayNumber());
        assertEquals(0, bus.tickOfDay());
    }

    @Test
    void testTickFrozenAgainstWallClock() {
        TimeEventBus bus = makeBus();
        bus.start();
        sleep(300);  // 0.3s of real time elapses
        assertEquals(0, bus.currentTick());  // Tick stays frozen at 0
        bus.stop();
    }

    // ── Fast-forward candidate computation ────────────────────

    @Test
    void testScheduledEventAndTaskCandidates() {
        TimeEventBus bus = makeBus();
        bus.start();
        bus.stop();
        bus.registerEvent(ev("e1"), 50);
        TimeEventBus.ScheduledTask task = bus.scheduleTask("t", "CEO", 10, 1, null);
        assertNotNull(task);
        assertEquals(10, bus.debugNextEventTick());
        bus.cancelTask(task.taskId);
        assertEquals(50, bus.debugNextEventTick());
    }

    @Test
    void testNextDayShiftStartAfterShiftEnd() {
        TimeEventBus bus = makeBus();
        bus.start();
        bus.stop();
        bus.debugSetTick(62);
        assertEquals(TICKS_PER_DAY, bus.debugNextEventTick());
    }

    @Test
    void testShiftEndBeforeShiftEnd() {
        TimeEventBus bus = makeBus();
        bus.start();
        bus.stop();
        bus.debugSetTick(30);
        assertEquals(SHIFT_END_TICK, bus.debugNextEventTick());
    }

    // ── SHIFT_START/SHIFT_END window detection + firing once per day ─

    @Test
    void testShiftStartFiresWhenWindowMissed() {
        TimeEventBus bus = makeBus();
        List<String> events = new ArrayList<>();
        bus.setEventSender(e -> events.add(e.eventType));
        bus.start();
        sleep(150);  // wait for the first check (tick 0 → Day 1 SHIFT_START)
        assertEquals(1, count(events, TimeEventBus.EVENT_SHIFT_START));
        jump(bus, 60);
        assertEquals(1, count(events, TimeEventBus.EVENT_SHIFT_END));
        // big jump into the Day 2 shift-start window (tick 145 → day2 tod=1): the window check must still fire
        jump(bus, 145);
        assertEquals(2, count(events, TimeEventBus.EVENT_SHIFT_START));
        jump(bus, 204);  // Day 2 shift end
        assertEquals(2, count(events, TimeEventBus.EVENT_SHIFT_START));
        assertEquals(2, count(events, TimeEventBus.EVENT_SHIFT_END));
        jump(bus, 294);  // Day 3 shift-start window
        assertEquals(3, count(events, TimeEventBus.EVENT_SHIFT_START));
        assertEquals(2, count(events, TimeEventBus.EVENT_SHIFT_END));
    }

    // ── Task fired dedup: no re-fire across days ──────────────

    @Test
    void testTaskFiresOnceAcrossDays() {
        TimeEventBus bus = makeBus();
        List<String> events = new ArrayList<>();
        bus.setEventSender(e -> events.add(e.eventType));
        bus.start();
        sleep(150);
        TimeEventBus.ScheduledTask task = bus.scheduleTask("Reminder", "CEO", 2, 1, null);
        jump(bus, 3);  // skip tick 2 → fires once, marked fired
        assertEquals(1, count(events, TimeEventBus.EVENT_TASK_DUE));
        assertTrue(task.fired);
        jump(bus, 145);  // Day 2 shift start: fired tasks must not be re-registered
        assertEquals(2, count(events, TimeEventBus.EVENT_SHIFT_START));
        assertEquals(1, count(events, TimeEventBus.EVENT_TASK_DUE));
    }

    // ── edit_task guard: cannot change to a past time ─────────

    @Test
    void testEditToPastRaises() {
        TimeEventBus bus = makeBus();
        bus.start();
        sleep(150);
        TimeEventBus.ScheduledTask task = bus.scheduleTask("t", "CEO", 5, 1, null);
        jump(bus, 6);  // tick 6 — past tick 5
        assertThrows(IllegalArgumentException.class, () -> bus.editTask(task.taskId, null, 3, null));
        assertThrows(IllegalArgumentException.class, () -> bus.editTask(task.taskId, null, 5, 1));
        TimeEventBus.ScheduledTask updated = bus.editTask(task.taskId, null, 20, null);
        assertNotNull(updated);
        assertEquals(20, updated.targetTick);
    }

    @Test
    void testEditToPastDayRaises() {
        TimeEventBus bus = makeBus();
        bus.start();
        sleep(150);
        TimeEventBus.ScheduledTask task = bus.scheduleTask("t", "CEO", 1, 2, null);
        jump(bus, 147);  // after Day 2 has started, moving the task back to Day 1 → rejected
        assertThrows(IllegalArgumentException.class, () -> bus.editTask(task.taskId, null, 1, 1));
    }

    // ── Scheduled task registration semantics ────────────────

    @Test
    void testTaskRegisteredOnceOnCreation() {
        TimeEventBus bus = makeBus();
        bus.start();
        sleep(150);
        bus.scheduleTask("Today's task", "r1", 5, 1, null);
        assertEquals(1, bus.tickSchedule.size());  // registered at creation time
        bus.debugLoadTodayTasksToBus();
        assertEquals(1, bus.tickSchedule.size());
        List<Types.Event> due = bus.checkDueEvents(999);
        assertEquals(1, due.size());
        assertEquals(TimeEventBus.EVENT_TASK_DUE, due.get(0).eventType);
    }

    @Test
    void testFutureTaskLoadedOnTargetDay() {
        TimeEventBus bus = makeBus();
        bus.start();
        sleep(150);
        TimeEventBus.ScheduledTask task = bus.scheduleTask("Tomorrow's task", "r1", 5, 2, null);
        assertTrue(task.eventId.isEmpty());  // next-day task at creation → only saved
        assertEquals(0, bus.tickSchedule.size());
        bus.debugSetTick(144);  // fast-forward to Day 2
        bus.debugLoadTodayTasksToBus();
        assertTrue(!task.eventId.isEmpty());  // now registered
        assertEquals(1, bus.tickSchedule.size());
        bus.debugLoadTodayTasksToBus();
        assertEquals(1, bus.tickSchedule.size());
    }

    @Test
    void testDuplicateRegisterWarns() {
        TimeEventBus bus = makeBus();
        bus.start();
        sleep(150);
        TimeEventBus.ScheduledTask task = bus.scheduleTask("Register only once", "r1", 5, 1, null);
        assertEquals(1, bus.tickSchedule.size());
        // abnormal path: duplicate registration → not added twice
        bus.debugRegisterTaskEventIfToday(task);
        assertEquals(1, bus.tickSchedule.size());
    }

    // ── Helpers ───────────────────────────────────────────────

    private static Types.Event ev(String eventType) {
        return new Types.Event("test", eventType, Types.Priority.NORMAL);
    }

    private static long count(List<String> events, String type) {
        return events.stream().filter(type::equals).count();
    }
}
