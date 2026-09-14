package com.agent.software.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryPolicyTest {

    @Test
    void emergencyAlwaysDeliveredImmediately() {
        for (AgentState state : AgentState.values()) {
            DeliveryPolicy p = DeliveryPolicy.forState(Priority.EMERGENCY, state);
            assertEquals(DeliveryMode.IMMEDIATE, p.mode(), "state=" + state);
            assertTrue(p.immediate());
        }
    }

    @Test
    void offDutyHoldsNonEmergencyWork() {
        assertEquals(DeliveryMode.HOLD_UNTIL_SHIFT,
                DeliveryPolicy.forState(Priority.NORMAL, AgentState.OFF_DUTY).mode());
    }

    @Test
    void wrappingUpIsHeldLikeOffDuty() {
        assertEquals(DeliveryMode.HOLD_UNTIL_SHIFT,
                DeliveryPolicy.forState(Priority.HIGH, AgentState.WRAPPING_UP).mode());
    }

    @Test
    void waitingDefersUntilReplyArrives() {
        assertEquals(DeliveryMode.DEFER_UNTIL_IDLE,
                DeliveryPolicy.forState(Priority.HIGH, AgentState.WAITING).mode());
    }

    @Test
    void availableRolesReceiveImmediately() {
        assertEquals(DeliveryMode.IMMEDIATE,
                DeliveryPolicy.forState(Priority.LOW, AgentState.IDLE).mode());
        assertEquals(DeliveryMode.IMMEDIATE,
                DeliveryPolicy.forState(Priority.HIGH, AgentState.BUSY).mode());
    }
}
