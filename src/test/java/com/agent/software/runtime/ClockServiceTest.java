package com.agent.software.runtime;

import com.agent.software.domain.Event;
import com.agent.software.domain.EventType;
import com.agent.software.domain.Payload;
import com.agent.software.domain.ShiftCalendar;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ClockPort;
import com.agent.software.ports.EventSink;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClockServiceTest {

    static final class RecordingSink implements EventSink {
        final List<Event> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(Event event) {
            events.add(event);
        }

        boolean saw(EventType type) {
            return events.stream().anyMatch(e -> e.type().equals(type));
        }
    }

    static final class FakeGate implements LifecycleGate {
        volatile boolean busy;
        volatile boolean idle = true;
        volatile boolean paused;
        volatile boolean ready;

        @Override
        public boolean anyBusy() {
            return busy;
        }

        @Override
        public boolean allIdle() {
            return idle;
        }

        @Override
        public boolean paused() {
            return paused;
        }

        @Override
        public boolean rolloverReady() {
            return ready;
        }

        @Override
        public void forceWrapUp() {
        }
    }

    private static ClockService clock(RecordingSink sink, FakeGate gate, double interval) {
        return new ClockService(ShiftCalendar.of(1.0, 8, 18), LocalDate.of(2026, 1, 5),
                sink, gate, new ClockService.ClockOptions(interval, interval, 1.0, 600.0));
    }

    private static boolean waitFor(BooleanSupplier condition, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    @Test
    void startPublishesShiftStartAndStopsCleanly() {
        RecordingSink sink = new RecordingSink();
        FakeGate gate = new FakeGate();
        ClockService service = clock(sink, gate, 0.02);
        service.start();
        try {
            assertTrue(waitFor(() -> sink.saw(EventType.SHIFT_START), 2000), "SHIFT_START not published");
        } finally {
            service.stop();
        }
        assertFalse(service.isRunning());
    }

    @Test
    void pauseFreezesTheTick() throws Exception {
        RecordingSink sink = new RecordingSink();
        FakeGate gate = new FakeGate();
        gate.paused = true;
        ClockService service = clock(sink, gate, 0.02);
        service.start();
        try {
            Thread.sleep(120);
            assertEquals(0, service.tick());
            assertFalse(sink.saw(EventType.SHIFT_START), "paused clock must not fire shift events");
        } finally {
            service.stop();
        }
    }

    @Test
    void idleTeamFastForwardsToTheNextEventTick() {
        RecordingSink sink = new RecordingSink();
        FakeGate gate = new FakeGate();
        ClockService service = clock(sink, gate, 0.02);
        service.start();
        try {
            assertTrue(waitFor(() -> service.tick() == 36_000, 3000),
                    "clock did not fast-forward to shift end, tick=" + service.tick());
            assertTrue(sink.saw(EventType.SHIFT_END));
        } finally {
            service.stop();
        }
    }

    @Test
    void scheduleAndCancelWithoutThreading() {
        ClockService service = clock(new RecordingSink(), new FakeGate(), 0.02);
        ClockPort.ScheduleHandle handle = service.schedule(
                new ClockPort.ScheduleRequest("review", RoleId.of("CEO"), 1, 120, Payload.empty()));
        assertEquals(1, service.schedules().size());
        assertTrue(service.cancel(handle.id()));
        assertTrue(service.schedules().isEmpty());
    }
}
