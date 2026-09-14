package com.agent.software.runtime;

import com.agent.software.domain.Event;
import com.agent.software.domain.EventType;
import com.agent.software.domain.Payload;
import com.agent.software.domain.ShiftCalendar;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ClockPort;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClockEngineTest {

    private final ShiftCalendar calendar = ShiftCalendar.of(1.0, 8, 18);

    private ClockEngine engine() {
        return new ClockEngine(calendar, LocalDate.of(2026, 1, 5));
    }

    private static boolean hasType(List<Event> events, EventType type) {
        return events.stream().anyMatch(e -> e.type().equals(type));
    }

    @Test
    void firesShiftStartOnceAtDayStart() {
        ClockEngine engine = engine();
        List<Event> first = engine.evaluate();
        assertEquals(1, first.size());
        assertEquals(EventType.SHIFT_START, first.get(0).type());
        assertTrue(engine.evaluate().isEmpty(), "SHIFT_START must fire once per day");
    }

    @Test
    void busyAdvanceStopsAtShiftEndAndFiresShiftEnd() {
        ClockEngine engine = engine();
        engine.evaluate();

        List<Event> events = engine.advanceBusy(40_000);

        assertEquals(36_000, engine.tick(), "busy time must not pass 18:00");
        assertEquals("18:00:00", engine.currentTime());
        assertTrue(hasType(events, EventType.SHIFT_END));
    }

    @Test
    void scheduledReminderFiresOnceForItsOwner() {
        ClockEngine engine = engine();
        engine.evaluate();
        RoleId ceo = RoleId.of("CEO");
        engine.addSchedule(new ClockPort.ScheduleRequest("standup", ceo, 1, 60, Payload.empty()));

        List<Event> events = engine.advanceBusy(60);
        List<Event> due = events.stream().filter(e -> e.type().equals(EventType.TASK_DUE)).toList();
        assertEquals(1, due.size());
        assertEquals(Set.of(ceo), due.get(0).recipients());

        assertTrue(engine.advanceBusy(1).stream().noneMatch(e -> e.type().equals(EventType.TASK_DUE)),
                "a reminder must not fire twice");
    }

    @Test
    void nextEventTickPrefersShiftEndThenNextShiftStart() {
        ClockEngine engine = engine();
        engine.evaluate();
        assertEquals(36_000, engine.nextEventTick(false).orElseThrow());

        engine.jumpTo(36_000);
        assertTrue(engine.nextEventTick(false).isEmpty(), "day rollover requires the ready gate");
        assertEquals(86_400, engine.nextEventTick(true).orElseThrow());
    }

    @Test
    void resetToDoesNotReplayAlreadyFiredEvents() {
        ClockEngine engine = engine();
        engine.evaluate();
        engine.resetTo(1, 100);
        assertTrue(engine.evaluate().isEmpty());
    }

    @Test
    void reminderPayloadIsCarriedIntoTheEvent() {
        ClockEngine engine = engine();
        engine.evaluate();
        engine.addSchedule(new ClockPort.ScheduleRequest("note reminder", RoleId.of("CEO"), 1, 10,
                Payload.of("note_title", "day1")));
        Event due = engine.advanceBusy(10).stream()
                .filter(e -> e.type().equals(EventType.TASK_DUE)).findFirst().orElseThrow();
        assertEquals("day1", due.payload().str("note_title", ""));
        assertEquals("note reminder", due.payload().str("description", ""));
    }
}
