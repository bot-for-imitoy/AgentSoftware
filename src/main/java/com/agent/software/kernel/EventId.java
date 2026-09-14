package com.agent.software.kernel;

import java.util.UUID;

/** Strongly typed event identifier. */
public record EventId(String value) {

    public EventId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("EventId must not be blank");
        }
        value = value.strip();
    }

    public static EventId of(String value) {
        return new EventId(value);
    }

    public static EventId newId() {
        return new EventId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    @Override
    public String toString() {
        return value;
    }
}
