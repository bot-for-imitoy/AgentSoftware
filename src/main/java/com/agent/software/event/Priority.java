package com.agent.software.event;

/**
 * 事件优先级：值越大越优先，队列按它排序。
 */
public enum Priority {
    LOW(1),
    NORMAL(3),
    HIGH(6),
    EMERGENCY(10);

    public final int value;

    Priority(int value) {
        this.value = value;
    }

    public static Priority from(int value) {
        Priority best = NORMAL;
        for (Priority p : values()) {
            if (p.value == value) {
                return p;
            }
        }
        return best;
    }

    public static Priority from(String s) {
        if (s == null || s.isBlank()) {
            return NORMAL;
        }
        for (Priority p : values()) {
            if (p.name().equalsIgnoreCase(s)) {
                return p;
            }
        }
        try {
            return from(Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return NORMAL;
        }
    }
}
