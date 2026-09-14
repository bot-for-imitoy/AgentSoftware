package com.agent.software.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShiftCalendarTest {

    private final ShiftCalendar calendar = ShiftCalendar.of(1.0, 8, 18);

    @Test
    void geometryDefaultsToOneSecondPerTick() {
        assertEquals(0, calendar.shiftStartTick());
        assertEquals(36_000, calendar.shiftEndTick());
        assertEquals(86_400, calendar.ticksPerDay());
    }

    @Test
    void tickZeroIsShiftStartAndShiftEndTickIsOffDuty() {
        assertEquals("08:00:00", calendar.timeOf(0));
        assertEquals("18:00:00", calendar.timeOf(36_000));
        assertTrue(calendar.isWorkingTick(0));
        assertFalse(calendar.isWorkingTick(36_000));
        assertTrue(calendar.isShiftEnd(36_000));
    }

    @Test
    void dayAndTickOfDayRoundTrip() {
        int tick = calendar.absoluteTick(3, 12_345);
        assertEquals(3, calendar.dayOf(tick));
        assertEquals(12_345, calendar.tickOfDay(tick));
        assertEquals(23_655, calendar.ticksUntilShiftEnd(tick));
    }

    @Test
    void coarseTicksRescaleGeometry() {
        ShiftCalendar coarse = ShiftCalendar.of(60.0, 8, 18);
        assertEquals(600, coarse.shiftEndTick());
        assertEquals(1_440, coarse.ticksPerDay());
        assertEquals("18:00:00", coarse.timeOf(600));
    }

    @Test
    void nextShiftStartIsTomorrowWhenTodayStarted() {
        assertEquals(calendar.absoluteTick(2, 0), calendar.nextShiftStartTick(10));
    }

    @Test
    void invalidTickLengthRejected() {
        assertThrows(IllegalArgumentException.class, () -> ShiftCalendar.of(0, 8, 18));
    }
}
