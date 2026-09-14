package com.agent.software.domain;

/**
 * Lifecycle-based delivery decision.
 *
 * <p>This replaces the removed three-layer content filter. It never inspects the
 * event payload, keywords or a salience score — it only prevents disturbing a
 * role that is off duty, wrapping up or synchronously waiting. Event cost is
 * controlled by the producer choosing recipients explicitly.
 */
public record DeliveryPolicy(DeliveryMode mode, String reason) {

    public DeliveryPolicy {
        mode = mode == null ? DeliveryMode.IMMEDIATE : mode;
        reason = reason == null ? "" : reason;
    }

    public static DeliveryPolicy forState(Priority priority, AgentState state) {
        if (priority == Priority.EMERGENCY) {
            return new DeliveryPolicy(DeliveryMode.IMMEDIATE, "emergency event");
        }
        if (state == null) {
            return new DeliveryPolicy(DeliveryMode.IMMEDIATE, "role state unknown");
        }
        return switch (state) {
            case WAITING -> new DeliveryPolicy(DeliveryMode.DEFER_UNTIL_IDLE, "role is waiting for a reply");
            case OFF_DUTY -> new DeliveryPolicy(DeliveryMode.HOLD_UNTIL_SHIFT, "role is off duty");
            case WRAPPING_UP -> new DeliveryPolicy(DeliveryMode.HOLD_UNTIL_SHIFT, "role is wrapping up");
            default -> new DeliveryPolicy(DeliveryMode.IMMEDIATE, "role is available");
        };
    }

    public boolean immediate() {
        return mode == DeliveryMode.IMMEDIATE;
    }
}
