package com.agent.software.ports;

import com.agent.software.domain.Payload;
import com.agent.software.kernel.RoleId;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Read access to the simulated clock plus timed-event scheduling.
 *
 * <p>The runtime never touches the clock thread: it queries the current tick and
 * the next event tick (for fast-forward) and schedules reminders through
 * {@link #schedule}.
 */
public interface ClockPort {

    int tick();

    int day();

    int tickOfDay();

    boolean isWorkingHours();

    boolean isShiftEnd();

    /** Human-readable simulated date/time and duty status. */
    String describe();

    /** Next scheduled fire tick after the current one, if any (drives fast-forward). */
    OptionalInt nextEventTick();

    ScheduleHandle schedule(ScheduleRequest request);

    boolean cancel(String handleId);

    /** A request to fire a {@code TASK_DUE} event for one role at a day/tick. */
    record ScheduleRequest(String description, RoleId owner, int day, int tick, Payload payload) {
        public ScheduleRequest {
            Objects.requireNonNull(owner, "owner");
            description = description == null ? "" : description;
            day = Math.max(1, day);
            tick = Math.max(0, tick);
            payload = payload == null ? Payload.empty() : payload;
        }
    }

    /** Opaque handle returned by {@link #schedule}. */
    record ScheduleHandle(String id) {
        public ScheduleHandle {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("ScheduleHandle id must not be blank");
            }
        }
    }
}
