package com.agent.software.domain;

/**
 * Event urgency. Higher is more urgent.
 *
 * <p>Also determines the urgency of the {@link Task} an event turns into, and
 * whether {@link DeliveryPolicy} may hold the task for a busy/off-duty role.
 */
public enum Priority {
    LOW(1),
    NORMAL(3),
    HIGH(6),
    EMERGENCY(10);

    private final int value;

    Priority(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    /** Map a numeric urgency back to a priority, defaulting to {@link #NORMAL}. */
    public static Priority from(int value) {
        for (Priority p : values()) {
            if (p.value == value) {
                return p;
            }
        }
        return NORMAL;
    }
}
