package com.agent.software.event;

/**
 * 事件类型。控制类事件（SHIFT_START / SHIFT_END）在下班时段仍会放行，
 * 其余类型下班后由 {@link EventBus} 暂存到次日。
 */
public enum EventType {
    SHIFT_START,
    SHIFT_END,
    TASK,
    TALK,
    NEW_MAIL,
    REST,
    TICK,
    CUSTOM;

    public static EventType from(String s) {
        if (s == null || s.isBlank()) {
            return CUSTOM;
        }
        for (EventType t : values()) {
            if (t.name().equalsIgnoreCase(s)) {
                return t;
            }
        }
        return CUSTOM;
    }

    /** 是否属于"下班也要放行"的控制事件。 */
    public boolean isControl() {
        return this == SHIFT_START || this == SHIFT_END;
    }
}
