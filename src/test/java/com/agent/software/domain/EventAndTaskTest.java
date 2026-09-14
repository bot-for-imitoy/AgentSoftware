package com.agent.software.domain;

import com.agent.software.kernel.EventId;
import com.agent.software.kernel.RoleId;
import com.agent.software.kernel.TaskId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventAndTaskTest {

    @Test
    void broadcastHasNoRecipients() {
        Event e = Event.broadcast("time", EventType.SHIFT_START, Priority.EMERGENCY, Payload.empty());
        assertTrue(e.isBroadcast());
        assertTrue(e.recipients().isEmpty());
        assertFalse(e.triggerTick().isPresent());
    }

    @Test
    void targetedKeepsOnlyNamedRecipients() {
        RoleId ceo = RoleId.of("CEO");
        Event e = Event.toRole("email", EventType.NEW_MAIL, Priority.NORMAL, Payload.of("title", "hi"), ceo);
        assertFalse(e.isBroadcast());
        assertEquals(Set.of(ceo), e.recipients());
    }

    @Test
    void recipientsAreDefensivelyCopied() {
        Set<RoleId> mutable = new LinkedHashSet<>();
        mutable.add(RoleId.of("CEO"));
        Event e = Event.targeted("x", EventType.of("custom"), Priority.LOW, Payload.empty(), mutable);
        mutable.add(RoleId.of("COO"));
        assertEquals(1, e.recipients().size());
        assertThrows(UnsupportedOperationException.class, () -> e.recipients().add(RoleId.of("HR")));
    }

    @Test
    void scheduledCopyCarriesTriggerTick() {
        Event e = Event.broadcast("time", EventType.SHIFT_END, Priority.EMERGENCY, Payload.empty());
        Event scheduled = e.scheduledAt(36_000);
        assertEquals(36_000, scheduled.triggerTick().orElseThrow());
        assertEquals(e.id(), scheduled.id());
    }

    @Test
    void taskLifecycleTransitions() {
        Task t = Task.create(6, "do the thing", "test", Payload.empty());
        assertEquals(TaskStatus.PENDING, t.status());
        t.assignTo("CEO");
        assertEquals("CEO", t.assignedRoleId());

        t.markRunning();
        assertEquals(TaskStatus.RUNNING, t.status());

        t.markDone("ok", 42);
        assertEquals(TaskStatus.DONE, t.status());
        assertEquals("ok", t.result());
        assertEquals(42, t.tokensConsumed());

        t.markFailed("[ERROR] boom", 7);
        assertEquals(TaskStatus.FAILED, t.status());
        assertEquals(7, t.tokensConsumed());
    }

    @Test
    void taskIdsAreUniqueAndUrgencyIsPositive() {
        Task a = Task.create(0, "a", "s", Payload.empty());
        Task b = Task.create(3, "b", "s", Payload.empty());
        assertEquals(1, a.urgency());
        assertFalse(a.id().equals(b.id()));
        assertThrows(IllegalArgumentException.class, () -> TaskId.of(" "));
    }

    @Test
    void eventIdMustNotBeBlank() {
        assertThrows(IllegalArgumentException.class, () -> EventId.of("  "));
    }
}
