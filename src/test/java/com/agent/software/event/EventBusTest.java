package com.agent.software.event;

import com.agent.software.AgentSystem;
import com.agent.software.io.WebInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 事件总线：排期、到期、下班暂存。 */
class EventBusTest {

    @Test
    void scheduleCancelAndDueOrder() {
        EventBus bus = new EventBus();
        Event late = Event.builder().at(10).type(EventType.TASK).build();
        Event early = Event.builder().at(5).type(EventType.TASK).build();
        bus.schedule(late);
        bus.schedule(early);

        assertEquals(2, bus.scheduled().size());
        assertEquals(5, bus.nextDue().targetTime);

        bus.tick(4);
        assertEquals(2, bus.scheduled().size());

        bus.tick(5);
        assertEquals(1, bus.scheduled().size());
        assertEquals(10, bus.nextDue().targetTime);

        assertTrue(bus.cancel(late.uuid));
        assertEquals(0, bus.scheduled().size());
    }

    @Test
    void offHoursNormalEventsAreHeldAndReleased(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            TimeBus tb = system.getTimeBus();
            EventBus bus = system.getEventBus();

            tb.setNow(40_000);           // 越过 18:00（tickOfDay >= 36000）
            assertTrue(!tb.isWorkingHours());

            Event normal = Event.builder().to("CEO").type(EventType.TASK)
                    .at(tb.now()).content("late work").build();
            bus.post(normal);
            assertEquals(1, bus.heldEvents().size());

            bus.releaseHeld();
            assertEquals(0, bus.heldEvents().size());
        } finally {
            system.stop();
        }
    }

    @Test
    void snapshotRoundTrip(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            EventBus bus = system.getEventBus();
            bus.schedule(Event.builder().at(1234).type(EventType.TASK).content("later").build());
            var snapshot = bus.snapshot();
            assertEquals(1, snapshot.size());
            assertNotNull(snapshot.get(0).get("uuid"));

            EventBus other = new EventBus();
            other.restore(snapshot, system.getRolePool());
            assertEquals(1, other.scheduled().size());
        } finally {
            system.stop();
        }
    }
}
