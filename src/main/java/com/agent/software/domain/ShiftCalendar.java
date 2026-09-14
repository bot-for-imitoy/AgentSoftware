package com.agent.software.domain;

/**
 * Pure tick ↔ simulated-clock arithmetic for the company shift.
 *
 * <p>One tick stands for {@code secondsPerTick} simulated seconds. A day cycle
 * is anchored at {@code shiftStartHour}:00:00, so tick 0 of a day is the shift
 * start and {@code shiftEndTick} is the shift end. This type performs no I/O and
 * owns no thread; {@code ClockService} drives it.
 */
public record ShiftCalendar(
        double secondsPerTick,
        int shiftStartTick,
        int shiftEndTick,
        int ticksPerDay,
        int shiftStartHour) {

    /** Simulated seconds in a full calendar day. */
    public static final int SIM_SECONDS_PER_DAY = 24 * 3600;

    public ShiftCalendar {
        if (!(secondsPerTick > 0)) {
            throw new IllegalArgumentException("secondsPerTick must be > 0, got " + secondsPerTick);
        }
        if (shiftEndTick <= shiftStartTick) {
            throw new IllegalArgumentException("shiftEndTick must be greater than shiftStartTick");
        }
        if (ticksPerDay <= shiftEndTick) {
            throw new IllegalArgumentException("ticksPerDay must be greater than shiftEndTick");
        }
    }

    /** Build from the shift wall-clock hours and the tick length. */
    public static ShiftCalendar of(double secondsPerTick, int shiftStartHour, int shiftEndHour) {
        int shiftSeconds = Math.max(1, (shiftEndHour - shiftStartHour) * 3600);
        int endTick = Math.max(1, (int) Math.round(shiftSeconds / secondsPerTick));
        int perDay = Math.max(endTick + 1, (int) Math.round(SIM_SECONDS_PER_DAY / secondsPerTick));
        return new ShiftCalendar(secondsPerTick, 0, endTick, perDay, shiftStartHour);
    }

    /** 1-based day number of an absolute tick. */
    public int dayOf(int tick) {
        return Math.floorDiv(tick, ticksPerDay) + 1;
    }

    /** Tick position within the day. */
    public int tickOfDay(int tick) {
        return Math.floorMod(tick, ticksPerDay);
    }

    /** Absolute tick of {@code tickOfDay} on the given 1-based day. */
    public int absoluteTick(int day, int tickOfDay) {
        return (Math.max(1, day) - 1) * ticksPerDay + tickOfDay;
    }

    /** Simulated wall clock "HH:MM:SS" for a (day-local) tick. */
    public String timeOf(int tick) {
        long total = (long) shiftStartHour * 3600 + (long) Math.floor(tick * secondsPerTick);
        int secondsOfDay = (int) Math.floorMod(total, SIM_SECONDS_PER_DAY);
        return String.format("%02d:%02d:%02d", secondsOfDay / 3600, (secondsOfDay % 3600) / 60, secondsOfDay % 60);
    }

    /** Whether the given absolute tick falls inside working hours. */
    public boolean isWorkingTick(int tick) {
        int tod = tickOfDay(tick);
        return shiftStartTick <= tod && tod < shiftEndTick;
    }

    /** Ticks left until the current day's shift end (never negative). */
    public int ticksUntilShiftEnd(int tick) {
        return Math.max(0, shiftEndTick - tickOfDay(tick));
    }

    /** Absolute tick of the next shift start strictly after {@code tick}. */
    public int nextShiftStartTick(int tick) {
        int day = dayOf(tick);
        int todayStart = absoluteTick(day, shiftStartTick);
        return tick < todayStart ? todayStart : absoluteTick(day + 1, shiftStartTick);
    }

    public boolean isShiftEnd(int tick) {
        return tickOfDay(tick) >= shiftEndTick;
    }

    /** Human description of the simulated time. */
    public String describe(int tick) {
        return timeOf(tickOfDay(tick)) + " Day " + dayOf(tick)
                + (isShiftEnd(tick) ? " (off duty)" : " (on duty)");
    }
}
