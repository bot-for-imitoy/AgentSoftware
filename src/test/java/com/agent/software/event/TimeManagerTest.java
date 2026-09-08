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
 *
 * The default tick geometry is 1 Tick = 1 simulated second: shift 08:00:00 (tick 0) →
 * 18:00:00 (tick 36000) and a 86400-tick day cycle.
 */
class TimeManagerTest {

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

    // ── Default geometry: 1 Tick = 1 simulated second ─────────

    @Test
    void testDefaultSecondsGeometry() {
        TimeEventBus bus = makeBus();
        assertEquals(1.0, bus.secondsPerTick);
        assertEquals(0, bus.shiftStartTick);
        assertEquals(36000, bus.shiftEndTick);   // 10 h of seconds = 08:00:00 → 18:00:00
        assertEquals(86400, bus.ticksPerDay);    // 24 h of seconds
        assertEquals(0, bus.taskTickMin);
        assertEquals(36000, bus.taskTickMax);    // reminders may only be scheduled within the shift
    }

    @Test
    void testSecondsPerTickRescalesGeometry() {
        TimeEventBus bus = makeBus();
        bus.setSecondsPerTick(2.0);  // one tick = 2 simulated seconds
        assertEquals(2.0, bus.secondsPerTick);
        assertEquals(18000, bus.shiftEndTick);
        assertEquals(43200, bus.ticksPerDay);
        assertEquals(18000, bus.taskTickMax);
        assertEquals("18:00:00", bus.tickToTime(18000));
        assertThrows(IllegalStateException.class, () -> {
            bus.start();
            bus.setSecondsPerTick(1.0);
        });
        bus.stop();
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
        bus.debugSetTick(bus.ticksPerDay);
        assertEquals(bus.ticksPerDay, bus.currentTick());
        assertEquals(2, bus.dayNumber());
        assertEquals(0, bus.tickOfDay());
    }

    @Test
    void testTickFrozenAgainstWallClock() {
        TimeEventBus bus = makeBus();
        bus.start();
        sleep(300);  // 0.3s of real time elapses
        assertEquals(0, bus.currentTick());  // Tick stays frozen at 0 (nothing busy)
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
        bus.debugSetTick(bus.shiftEndTick + 2);
        assertEquals(bus.ticksPerDay, bus.debugNextEventTick());
    }

    @Test
    void testShiftEndBeforeShiftEnd() {
        TimeEventBus bus = makeBus();
        bus.start();
        bus.stop();
        bus.debugSetTick(bus.shiftEndTick / 2);
        assertEquals(bus.shiftEndTick, bus.debugNextEventTick());
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
        jump(bus, bus.shiftEndTick);
        assertEquals(1, count(events, TimeEventBus.EVENT_SHIFT_END));
        // big jump into the Day 2 shift-start window (day 2, tick-of-day 1): the window check must still fire
        jump(bus, bus.ticksPerDay + 1);
        assertEquals(2, count(events, TimeEventBus.EVENT_SHIFT_START));
        jump(bus, bus.ticksPerDay + bus.shiftEndTick);  // Day 2 shift end
        assertEquals(2, count(events, TimeEventBus.EVENT_SHIFT_START));
        assertEquals(2, count(events, TimeEventBus.EVENT_SHIFT_END));
        jump(bus, 2 * bus.ticksPerDay + 1);  // Day 3 shift-start window
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
        jump(bus, bus.ticksPerDay + 1);  // Day 2 shift start: fired tasks must not be re-registered
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
        jump(bus, bus.ticksPerDay + 3);  // after Day 2 has started, moving the task back to Day 1 → rejected
        assertThrows(IllegalArgumentException.class, () -> bus.editTask(task.taskId, null, 1, 1));
    }

    // ── Task tick bounds: reminders only within the shift (0~36000 default) ─

    @Test
    void testTaskTickBoundsAreTheShiftRange() {
        TimeEventBus bus = makeBus();
        assertThrows(IllegalArgumentException.class,
                () -> bus.scheduleTask("too late", "CEO", bus.taskTickMax + 1, 1, null));
        bus.scheduleTask("at shift end", "CEO", bus.taskTickMax, 1, null);  // 18:00:00 allowed
        bus.scheduleTask("at shift start", "CEO", 0, 1, null);              // 08:00:00 allowed
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
        bus.debugSetTick(bus.ticksPerDay);  // fast-forward to Day 2
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

    // ── Simulated wall clock (08:00:00 anchor, seconds display, calendar dates) ─────

    @Test
    void testTickToTimeAnchoredAtEight() {
        TimeEventBus bus = makeBus();
        assertEquals("08:00:00", bus.tickToTime(0));         // shift start
        assertEquals("18:00:00", bus.tickToTime(36000));     // shift end (10 h in seconds)
        assertEquals("09:00:00", bus.tickToTime(3600));      // 1 h in
        assertEquals("08:00:09", bus.tickToTime(9));         // tick = 1 simulated second
        assertEquals("14:00:00", bus.tickToTime(21600));     // 6 h in
        assertEquals("00:00:00", bus.tickToTime(57600));     // 08:00 + 16 h → crosses midnight
        assertEquals("07:59:59", bus.tickToTime(86399));
        assertEquals("08:00:00", bus.tickToTime(86400));     // next day's start (wraps)
        assertEquals("08:00:00", bus.shiftStartTime());
        assertEquals("18:00:00", bus.shiftEndTime());
    }

    @Test
    void testCalendarDateMath() {
        TimeEventBus bus = makeBus();
        bus.setBaseDate(java.time.LocalDate.of(2025, 1, 6));
        bus.debugSetTick(0);
        assertEquals(1, bus.dayNumber());
        assertEquals("2025-01-06 08:00:00", bus.currentDateTime());
        bus.debugSetTick(bus.shiftEndTick);
        assertEquals("2025-01-06 18:00:00", bus.currentDateTime());
        bus.debugSetTick(60000);  // 08:00 + 16h40m → 00:40:00 of the next calendar date
        assertEquals(1, bus.dayNumber());                   // the tick cycle still belongs to day 1
        assertEquals("2025-01-07 00:40:00", bus.currentDateTime());
        bus.debugSetTick(bus.ticksPerDay);                  // next day's 08:00:00
        assertEquals(2, bus.dayNumber());
        assertEquals("2025-01-07 08:00:00", bus.currentDateTime());
    }

    @Test
    void testDescribeCarriesCalendarClock() {
        TimeEventBus bus = makeBus();
        bus.setBaseDate(java.time.LocalDate.of(2025, 1, 6));
        bus.start();
        sleep(150);
        String onDuty = bus.describe();
        assertTrue(onDuty.contains("2025-01-06 08:00:00"), onDuty);
        assertTrue(onDuty.contains("on duty"), onDuty);
        bus.debugSetTick(bus.shiftEndTick);
        assertTrue(bus.describe().contains("off duty"), bus.describe());
        bus.stop();
    }

    // ── Busy clock: simulated time flows while roles work, capped at 18:00 ─

    @Test
    void testBusyAdvanceFoldsSimSecondsIntoTicks() {
        TimeEventBus bus = makeBus();
        bus.start();
        sleep(150);
        assertEquals(0, bus.currentTick());  // nothing busy → clock frozen
        bus.debugAdvanceBusySimSeconds(95);  // 95 simulated seconds of work → 95 ticks (1 tick = 1 s)
        assertEquals(95, bus.tickOfDay());
        assertEquals("08:01:35", bus.currentTime());
        bus.stop();
    }

    @Test
    void testBusyAdvanceCappedAtShiftEnd() {
        TimeEventBus bus = makeBus();
        bus.debugSetTick(bus.shiftEndTick - 5);  // 17:59:55
        bus.debugAdvanceBusySimSeconds(100_000);  // no amount of busy work may pass 18:00:00
        assertEquals(bus.shiftEndTick, bus.tickOfDay());
        assertEquals("18:00:00", bus.currentTime());
        bus.debugAdvanceBusySimSeconds(100_000);  // wrap-up runs with the clock frozen at 18:00:00
        assertEquals(bus.shiftEndTick, bus.tickOfDay());
    }

    @Test
    void testBusyRemainderCarriesSubTick() {
        TimeEventBus bus = makeBus();
        bus.setSecondsPerTick(2.0);  // one tick = 2 simulated seconds
        bus.debugAdvanceBusySimSeconds(1);  // less than one tick
        assertEquals(0, bus.tickOfDay());
        bus.debugAdvanceBusySimSeconds(3);  // 4 sim seconds total → 2 complete ticks
        assertEquals(2, bus.tickOfDay());
        assertEquals("08:00:04", bus.currentTime());
    }

    // ── Clock pause: the simulated clock freezes while paused ─

    @Test
    void testClockPauseFreezesAndResumeContinues() {
        TimeEventBus bus = makeBus();
        bus.simSecondsPerRealSecond = 100;   // busy time races (1 real second = 100 simulated seconds)
        bus.setBusyChecker(() -> true);       // someone is always working → the busy clock runs
        bus.start();
        try {
            // wait until the busy clock has visibly advanced
            long deadline = System.currentTimeMillis() + 5000;
            while (bus.currentTick() < 50 && System.currentTimeMillis() < deadline) {
                sleep(50);
            }
            assertTrue(bus.currentTick() >= 50, "busy clock should advance, tick=" + bus.currentTick());

            bus.setClockPaused(true);
            sleep(400);   // let the tick loop settle into the paused branch
            int frozen = bus.currentTick();
            sleep(800);   // several busy-poll periods elapse
            assertEquals(frozen, bus.currentTick(), "the clock must not advance while paused");

            bus.setClockPaused(false);
            deadline = System.currentTimeMillis() + 5000;
            while (bus.currentTick() <= frozen && System.currentTimeMillis() < deadline) {
                sleep(50);
            }
            assertTrue(bus.currentTick() > frozen, "the clock must resume advancing after unpause");
        } finally {
            bus.stop();
        }
    }

    // ── Day rollover gate: the next 08:00 is reachable only after the wrap-up ─

    @Test
    void testNextDayReachableOnlyWhenRolloverReady() {
        TimeEventBus bus = makeBus();
        bus.debugSetTick(bus.shiftEndTick + 2);
        // no gate installed → next-day shift start reachable (standalone/legacy behavior)
        assertEquals(bus.ticksPerDay, bus.debugNextEventTick());
        // gate installed but the team has not wrapped up → the clock waits at 18:00
        bus.setRolloverReadyChecker(() -> false);
        assertNull(bus.debugNextEventTick());
        bus.setRolloverReadyChecker(() -> true);
        assertEquals(bus.ticksPerDay, bus.debugNextEventTick());
    }

    // ── Helpers ───────────────────────────────────────────────

    private static Types.Event ev(String eventType) {
        return new Types.Event("test", eventType, Types.Priority.NORMAL);
    }

    private static long count(List<String> events, String type) {
        return events.stream().filter(type::equals).count();
    }
}
