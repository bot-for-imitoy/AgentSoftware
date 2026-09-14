package com.agent.software.kernel;

import java.util.UUID;

/** Strongly typed task identifier. */
public record TaskId(String value) {

    public TaskId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TaskId must not be blank");
        }
        value = value.strip();
    }

    public static TaskId of(String value) {
        return new TaskId(value);
    }

    public static TaskId newId() {
        return new TaskId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
    }

    @Override
    public String toString() {
        return value;
    }
}
