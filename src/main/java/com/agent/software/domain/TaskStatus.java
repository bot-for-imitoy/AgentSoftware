package com.agent.software.domain;

/** Lifecycle state of a {@link Task}. */
public enum TaskStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED;

    /** Stable lower-case name used in persisted state and traces. */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static TaskStatus fromWire(String value) {
        if (value == null || value.isBlank()) {
            return PENDING;
        }
        for (TaskStatus s : values()) {
            if (s.wireName().equalsIgnoreCase(value.strip())) {
                return s;
            }
        }
        return PENDING;
    }
}
