package com.agent.software.runtime;

import com.agent.software.domain.Event;
import com.agent.software.domain.EventType;
import com.agent.software.domain.Payload;
import com.agent.software.domain.Priority;
import com.agent.software.domain.ScheduleEntry;
import com.agent.software.domain.ShiftCalendar;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ClockPort;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Pure clock state machine: tick arithmetic, shift events, due reminders and
 * day rollover. No threads, no I/O — the threaded {@link ClockService} drives it.
 *
 * <p>Two rules from the legacy clock are preserved:
 * busy time never advances past the shift end, and the clock may only jump to the
 * next day once {@code rolloverReady} is true.
 */
public final class ClockEngine {

    private final ShiftCalendar calendar;
    private final Map<String, ScheduleEntry> schedules = new LinkedHashMap<>();

    private int tick;
    private LocalDate baseDate;
    private int firedDay;
    private boolean firedStart;
    private boolean firedEnd;
    private double busyAccum;

    public ClockEngine(ShiftCalendar calendar, LocalDate baseDate) {
        this.calendar = calendar;
        this.baseDate = baseDate == null ? LocalDate.now() : baseDate;
    }

    public ShiftCalendar calendar() {
        return calendar;
    }

    public int tick() {
        return tick;
    }

    public int day() {
        return calendar.dayOf(tick);
    }

    public int tickOfDay() {
        return calendar.tickOfDay(tick);
    }

    public LocalDate baseDate() {
        return baseDate;
    }

    public void setBaseDate(LocalDate date) {
        this.baseDate = date == null ? LocalDate.now() : date;
    }

    /** Current simulated calendar date (day cycles are calendar-day based). */
    public LocalDate currentDate() {
        return baseDate.plusDays((long) day() - 1);
    }

    public String currentDateString() {
        return currentDate().toString();
    }

    public String currentTime() {
        return calendar.timeOf(tickOfDay());
    }

    public String currentDateTime() {
        return currentDateString() + " " + currentTime();
    }

    public String describe() {
        return currentDateTime() + " Day " + day() + (calendar.isShiftEnd(tick) ? " (off duty)" : " (on duty)");
    }

    /** Resume at a given day/tick; already-fired events are not replayed. */
    public void resetTo(int day, int tickOfDay) {
        this.tick = calendar.absoluteTick(day, tickOfDay);
        this.firedDay = day;
        this.firedStart = calendar.isWorkingTick(tick);
        this.firedEnd = calendar.isShiftEnd(tick);
        this.busyAccum = 0;
    }

    /**
     * Advance the clock by simulated busy seconds and return any events that
     * become due. Busy time is discarded once the shift has ended.
     */
    public List<Event> advanceBusy(double simSeconds) {
        if (simSeconds <= 0) {
            return List.of();
        }
        if (calendar.isShiftEnd(tick)) {
            busyAccum = 0;
            return List.of();
        }
        busyAccum += simSeconds;
        while (busyAccum >= calendar.secondsPerTick()) {
            int available = calendar.shiftEndTick() - tickOfDay();
            if (available <= 0) {
                busyAccum = 0;
                break;
            }
            int step = Math.min((int) Math.floor(busyAccum / calendar.secondsPerTick()), available);
            busyAccum -= step * calendar.secondsPerTick();
            tick += step;
        }
        return evaluate();
    }

    /** Jump forward to an absolute tick (fast-forward / rollover) and evaluate events. */
    public List<Event> jumpTo(int absoluteTick) {
        if (absoluteTick > tick) {
            tick = absoluteTick;
            busyAccum = 0;
        }
        return evaluate();
    }

    /** Fire shift and reminder events due at the current tick. */
    public List<Event> evaluate() {
        List<Event> out = new ArrayList<>();
        int day = day();
        if (day != firedDay) {
            firedDay = day;
            firedStart = false;
            firedEnd = false;
            busyAccum = 0;
        }
        if (!firedStart && calendar.isWorkingTick(tick)) {
            firedStart = true;
            out.add(shiftEvent(EventType.SHIFT_START));
        }
        if (!firedEnd && calendar.isShiftEnd(tick)) {
            firedEnd = true;
            out.add(shiftEvent(EventType.SHIFT_END));
        }
        for (ScheduleEntry entry : new ArrayList<>(schedules.values())) {
            if (!entry.fired() && entry.absoluteTick(calendar.ticksPerDay()) <= tick) {
                schedules.put(entry.id(), entry.asFired());
                out.add(taskDueEvent(entry));
            }
        }
        return out;
    }

    /** Next tick worth jumping to; empty when nothing is pending. */
    public OptionalInt nextEventTick(boolean rolloverReady) {
        int best = Integer.MAX_VALUE;
        for (ScheduleEntry entry : schedules.values()) {
            if (!entry.fired()) {
                best = Math.min(best, entry.absoluteTick(calendar.ticksPerDay()));
            }
        }
        int shiftEndAbsolute = calendar.absoluteTick(day(), calendar.shiftEndTick());
        if (shiftEndAbsolute > tick) {
            best = Math.min(best, shiftEndAbsolute);
        }
        if (calendar.isShiftEnd(tick) && rolloverReady) {
            int nextStart = calendar.absoluteTick(day() + 1, calendar.shiftStartTick());
            if (nextStart > tick) {
                best = Math.min(best, nextStart);
            }
        }
        return best == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(best);
    }

    public ScheduleEntry addSchedule(ClockPort.ScheduleRequest request) {
        ScheduleEntry entry = new ScheduleEntry(
                UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                request.owner(), request.description(), request.day(), request.tick(), request.payload(), false);
        schedules.put(entry.id(), entry);
        return entry;
    }

    public boolean removeSchedule(String id) {
        return schedules.remove(id) != null;
    }

    public List<ScheduleEntry> schedules() {
        return List.copyOf(schedules.values());
    }

    private Event shiftEvent(EventType type) {
        Payload payload = Payload.of("tick", tick)
                .with("day", day())
                .with("date", currentDateString())
                .with("time", currentTime())
                .with("shift", type.value());
        return Event.broadcast("time", type, Priority.EMERGENCY, payload);
    }

    private Event taskDueEvent(ScheduleEntry entry) {
        Payload payload = Payload.of("task_id", entry.id())
                .with("description", entry.description())
                .with("tick", entry.tick())
                .with("day", entry.day())
                .mergedWith(entry.payload());
        RoleId owner = entry.owner();
        return Event.toRole("task", EventType.TASK_DUE, Priority.NORMAL, payload, owner);
    }
}
