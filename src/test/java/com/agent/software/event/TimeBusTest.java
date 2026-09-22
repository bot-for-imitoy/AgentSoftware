package com.agent.software.event;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模拟时钟：tick、班次、日历、暂停、观察者。 */
class TimeBusTest {

    @Test
    void tickOfDayAndShiftWindow() {
        TimeBus tb = new TimeBus(LocalDate.of(2026, 1, 1));
        assertEquals(0, tb.now());
        assertEquals(1, tb.getDay());
        assertEquals("08:00", tb.currentTime());
        assertTrue(tb.isWorkingHours());

        tb.advance(60);
        assertEquals(60, tb.now());
        assertEquals("08:01", tb.currentTime());

        tb.advanceTo(35_999);
        assertTrue(tb.isWorkingHours());

        tb.advanceTo(36_000);
        assertFalse(tb.isWorkingHours());
        assertEquals(36_000, tb.getTickOfDay());
        assertEquals(1, tb.getDay());

        tb.advanceTo(86_400);
        assertEquals(2, tb.getDay());
        assertEquals(0, tb.getTickOfDay());
        assertEquals("2026-01-02", tb.currentDate().toString());
        assertTrue(tb.isWorkingHours());
    }

    @Test
    void pauseFlagAndTickListeners() {
        TimeBus tb = new TimeBus(LocalDate.of(2026, 1, 1));
        AtomicInteger ticks = new AtomicInteger();
        java.util.function.Consumer<TimeBus> listener = t -> ticks.incrementAndGet();
        tb.addTickListener(listener);

        tb.advance(3);
        assertEquals(1, ticks.get());     // advanceTo 只通知一次，不按 tick 次数通知

        tb.pause();
        assertTrue(tb.isPaused());
        tb.resume();
        assertFalse(tb.isPaused());

        tb.removeTickListener(listener);
        tb.advance(1);
        assertEquals(1, ticks.get());
    }

    @Test
    void setNowDoesNotMoveBackwards() {
        TimeBus tb = new TimeBus(LocalDate.of(2026, 1, 1));
        tb.advanceTo(100);
        tb.advanceTo(50);
        assertEquals(100, tb.now());
        tb.advance(-5);
        assertEquals(100, tb.now());
    }
}
