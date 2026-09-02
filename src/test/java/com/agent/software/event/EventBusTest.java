package com.agent.software.event;

import com.agent.software.core.Types;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EventBus schedule table tests (the Java counterpart of the Python test_event_bus.py).
 */
class EventBusTest {

    private static Types.Event ev(String eventType) {
        return new Types.Event("test", eventType, Types.Priority.NORMAL);
    }

    @Test
    void testScheduledRegisterAndDue() {
        EventBus bus = new EventBus();
        String eid = bus.registerEvent(ev("A"), 5);
        bus.registerEvent(ev("B"), 3);
        List<Map<String, Object>> scheduled = bus.listScheduledEvents();
        assertEquals(List.of(3, 5), scheduled.stream().map(s -> s.get("tick")).toList());
        // due events are popped (already removed from the schedule table)
        List<Types.Event> due = bus.checkDueEvents(4);
        assertEquals(List.of("B"), due.stream().map(e -> e.eventType).toList());
        // not-yet-due events remain in the table → can be cancelled
        assertTrue(bus.cancelEvent(eid));
    }

    @Test
    void testCancelEvent() {
        EventBus bus = new EventBus();
        String eid = bus.registerEvent(ev("X"), 7);
        assertTrue(bus.cancelEvent(eid));
        assertFalse(bus.cancelEvent(eid));
        assertTrue(bus.listScheduledEvents().isEmpty());
    }

    @Test
    void testTimeEventBusImmediateDispatches() {
        TimeEventBus bus = new TimeEventBus();
        bus.checkInterval = 0.05;
        List<String> sent = new ArrayList<>();
        bus.setEventSender(e -> sent.add(e.eventType));
        bus.registerEvent(ev("IMMEDIATE"), null);
        assertEquals(List.of("IMMEDIATE"), sent);
    }

    @Test
    void testScheduledNotDispatchedImmediately() {
        TimeEventBus bus = new TimeEventBus();
        bus.checkInterval = 0.05;
        List<String> sent = new ArrayList<>();
        bus.setEventSender(e -> sent.add(e.eventType));
        bus.registerEvent(ev("LATER"), 100);
        assertTrue(sent.isEmpty());
        assertEquals(1, bus.listScheduledEvents().size());
    }
}
