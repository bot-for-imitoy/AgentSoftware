package com.agent.software.runtime;

import com.agent.software.domain.Event;
import com.agent.software.domain.ShiftCalendar;
import com.agent.software.ports.ClockPort;
import com.agent.software.ports.EventSink;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;

/**
 * Threaded clock: owns the tick thread, advances the {@link ClockEngine} while
 * the team works, fast-forwards when everyone is idle and publishes shift /
 * reminder events through the {@link EventSink}.
 *
 * <p>Replaces the legacy {@code TimeEventBus} time thread. All event decisions
 * live in the pure engine; this class only schedules and publishes.
 */
public final class ClockService implements ClockPort {

    private static final long BUSY_POLL_MILLIS = 250;
    private static final long PAUSED_POLL_MILLIS = 200;

    /** Tunables for the clock thread. */
    public record ClockOptions(double checkIntervalSeconds, double fastForwardIdleSeconds,
                               double simSecondsPerRealSecond, double wrapUpGraceSeconds) {
        public static ClockOptions defaults() {
            return new ClockOptions(30.0, 60.0, 1.0, 600.0);
        }
    }

    private final ClockEngine engine;
    private final EventSink sink;
    private final LifecycleGate gate;
    private final ClockOptions options;

    private volatile boolean running;
    private Thread thread;
    private Double idleSince;
    private Double shiftEndedAt;

    public ClockService(ShiftCalendar calendar, LocalDate baseDate,
                        EventSink sink, LifecycleGate gate, ClockOptions options) {
        this.engine = new ClockEngine(calendar, baseDate);
        this.sink = sink;
        this.gate = gate;
        this.options = options == null ? ClockOptions.defaults() : options;
    }

    // ── ClockPort ──────────────────────────────────────────────────────

    @Override
    public int tick() {
        return engine.tick();
    }

    @Override
    public int day() {
        return engine.day();
    }

    @Override
    public int tickOfDay() {
        return engine.tickOfDay();
    }

    @Override
    public boolean isWorkingHours() {
        return engine.calendar().isWorkingTick(engine.tick());
    }

    @Override
    public boolean isShiftEnd() {
        return engine.calendar().isShiftEnd(engine.tick());
    }

    @Override
    public OptionalInt nextEventTick() {
        return engine.nextEventTick(gate != null && gate.rolloverReady());
    }

    @Override
    public ScheduleHandle schedule(ScheduleRequest request) {
        return new ScheduleHandle(engine.addSchedule(request).id());
    }

    @Override
    public boolean cancel(String handleId) {
        return engine.removeSchedule(handleId);
    }

    // ── extra read-only surface ────────────────────────────────────────

    public ClockEngine engine() {
        return engine;
    }

    public String currentDateString() {
        return engine.currentDateString();
    }

    public String currentTime() {
        return engine.currentTime();
    }

    public String currentDateTime() {
        return engine.currentDateTime();
    }

    public String describe() {
        return engine.describe();
    }

    /** Resume after restore at a given day/tick. */
    public void resetTo(int day, int tickOfDay) {
        engine.resetTo(day, tickOfDay);
    }

    public List<com.agent.software.domain.ScheduleEntry> schedules() {
        return engine.schedules();
    }

    // ── lifecycle ──────────────────────────────────────────────────────

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("clock").unstarted(this::loop);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    // ── thread body ────────────────────────────────────────────────────

    private void loop() {
        long lastBusyNanos = System.nanoTime();
        boolean wasBusy = false;
        while (running) {
            if (gate != null && gate.paused()) {
                wasBusy = false;
                idleSince = null;
                lastBusyNanos = System.nanoTime();
                sleep(PAUSED_POLL_MILLIS);
                continue;
            }
            long now = System.nanoTime();
            boolean busy = gate != null && gate.anyBusy();
            try {
                if (busy) {
                    if (!wasBusy) {
                        lastBusyNanos = now;
                    }
                    double realSeconds = (now - lastBusyNanos) / 1_000_000_000.0;
                    publish(engine.advanceBusy(realSeconds * options.simSecondsPerRealSecond()));
                    lastBusyNanos = now;
                } else {
                    publish(engine.evaluate());
                }
                wasBusy = busy;
                fastForward();
                wrapUpForce();
            } catch (RuntimeException ignored) {
                // a misbehaving gate or sink must never kill the clock thread
            }
            sleep(busy ? BUSY_POLL_MILLIS : (long) (options.checkIntervalSeconds() * 1000));
        }
    }

    private void fastForward() {
        if (gate == null) {
            return;
        }
        if (!gate.allIdle()) {
            idleSince = null;
            return;
        }
        double now = System.currentTimeMillis() / 1000.0;
        if (idleSince == null) {
            idleSince = now;
            return;
        }
        if (now - idleSince < options.fastForwardIdleSeconds()) {
            return;
        }
        OptionalInt target = engine.nextEventTick(gate.rolloverReady());
        if (target.isEmpty() || target.getAsInt() <= engine.tick()) {
            idleSince = now;
            return;
        }
        publish(engine.jumpTo(target.getAsInt()));
        idleSince = null;
    }

    private void wrapUpForce() {
        if (gate == null || options.wrapUpGraceSeconds() <= 0) {
            return;
        }
        if (!engine.calendar().isShiftEnd(engine.tick())) {
            shiftEndedAt = null;
            return;
        }
        if (gate.rolloverReady()) {
            shiftEndedAt = null;
            return;
        }
        double now = System.currentTimeMillis() / 1000.0;
        if (shiftEndedAt == null) {
            shiftEndedAt = now;
            return;
        }
        if (now - shiftEndedAt >= options.wrapUpGraceSeconds()) {
            shiftEndedAt = null;
            gate.forceWrapUp();
        }
    }

    private void publish(List<Event> events) {
        if (sink == null || events == null) {
            return;
        }
        for (Event event : events) {
            sink.publish(event);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
