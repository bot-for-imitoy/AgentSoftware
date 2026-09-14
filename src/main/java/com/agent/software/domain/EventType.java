package com.agent.software.domain;

/**
 * Event kind, modelled as a value type so known constants and producer-defined
 * kinds share one type.
 */
public record EventType(String value) {

    public static final EventType SHIFT_START = new EventType("SHIFT_START");
    public static final EventType SHIFT_END = new EventType("SHIFT_END");
    public static final EventType TASK_DUE = new EventType("TASK_DUE");
    public static final EventType NEW_MAIL = new EventType("NEW_MAIL");
    public static final EventType ROLE_TALK = new EventType("ROLE_TALK");
    public static final EventType TASK_ASSIGNED = new EventType("TASK_ASSIGNED");

    public EventType {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("EventType must not be blank");
        }
        value = value.strip();
    }

    public static EventType of(String value) {
        return new EventType(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
