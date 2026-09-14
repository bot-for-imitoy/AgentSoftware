package com.agent.software.domain;

import com.agent.software.kernel.RoleId;

import java.util.Objects;

/**
 * A timed or reminder entry that fires a {@link EventType#TASK_DUE} event for its
 * owner at a given day/tick.
 *
 * <p>Unifies what used to be split between the note reminder store and the
 * clock's scheduled-task table.
 */
public record ScheduleEntry(
        String id,
        RoleId owner,
        String description,
        int day,
        int tick,
        Payload payload,
        boolean fired) {

    public ScheduleEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        description = description == null ? "" : description;
        day = Math.max(1, day);
        tick = Math.max(0, tick);
        payload = payload == null ? Payload.empty() : payload;
    }

    /** Absolute tick at which this entry fires. */
    public int absoluteTick(int ticksPerDay) {
        return (day - 1) * ticksPerDay + tick;
    }

    public ScheduleEntry asFired() {
        return new ScheduleEntry(id, owner, description, day, tick, payload, true);
    }
}
