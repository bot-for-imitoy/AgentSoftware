package com.agent.software.event;

import com.agent.software.AgentSystem;
import com.agent.software.core.Types;
import com.agent.software.io.StdInput;
import com.agent.software.role.AgentRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scheduled task reminders (TASK_DUE, source=task — e.g. the Day-1 "talk to the user" kickoff and
 * every write_note reminder) fire exactly once: TimeEventBus marks them fired before dispatching.
 * They must therefore always reach the target role's queue even when that role is momentarily
 * WAIT (blocked on a synchronous talk reply) or OFF_DUTY — otherwise the reminder is silently lost
 * and, for example, nobody ever comes to ask the client for the requirements.
 *
 * <p>Non-reminder targeted notifications (NEW_MAIL etc.) keep the state mask: an OFF_DUTY / WAIT
 * role is not disturbed by non-urgent targeted events.
 */
class TaskDueStateMaskTest {

    @TempDir
    Path tmp;

    private AgentSystem make(Path dir) {
        return new AgentSystem(dir, null, List.of("CEO", "CTO"), 30.0, false, new StdInput());
    }

    /** A one-shot scheduled-reminder event exactly as TimeEventBus builds it (taskToEvent). */
    private static Types.Event taskDue(String targetRole, String taskId) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("task_id", taskId);
        payload.put("description", "[Note Reminder] Talk to the user about the project requirements");
        payload.put("tick", 60);
        payload.put("day", 1);
        payload.put("owner_role", targetRole);
        return new Types.Event("task", TimeEventBus.EVENT_TASK_DUE,
                Types.Priority.NORMAL, payload, targetRole);
    }

    // ── WAIT (blocked on a synchronous talk reply): the reminder must still be queued ─

    @Test
    void taskDueReachesACeoInWait() {
        AgentSystem s = make(tmp.resolve("a"));
        AgentRole ceo = s.getRole("CEO");
        ceo.beginWait("CTO");
        try {
            Map<String, Map<String, Object>> results = s.trigger(taskDue("CEO", "remind-1"));
            Map<String, Object> r = results.get("CEO");
            assertTrue(Boolean.TRUE.equals(r.get("accepted")),
                    "a one-shot task reminder must be accepted even while WAIT: " + r);
            assertEquals(1, ceo.queueDepth());
            assertTrue(ceo.pendingTasks().get(0).description.contains("Talk to the user"));
        } finally {
            ceo.endWait();
            s.timeManager.stop();
        }
    }

    // ── OFF_DUTY (already wrapped up): the reminder is queued and resumes at the next shift ─

    @Test
    void taskDueReachesAnOffDutyRole() {
        AgentSystem s = make(tmp.resolve("b"));
        AgentRole ceo = s.getRole("CEO");
        ceo.setState(Types.AgentState.OFF_DUTY);
        try {
            Map<String, Map<String, Object>> results = s.trigger(taskDue("CEO", "remind-2"));
            Map<String, Object> r = results.get("CEO");
            assertTrue(Boolean.TRUE.equals(r.get("accepted")),
                    "a one-shot task reminder must be accepted even while OFF_DUTY: " + r);
            assertEquals(1, ceo.queueDepth());
        } finally {
            s.timeManager.stop();
        }
    }

    // ── Non-reminder targeted events keep the state mask (e.g. NEW_MAIL must not wake rest) ─

    @Test
    void nonReminderTargetedEventsStillRespectTheStateMask() {
        AgentSystem s = make(tmp.resolve("c"));
        AgentRole ceo = s.getRole("CEO");
        ceo.setState(Types.AgentState.OFF_DUTY);
        try {
            Map<String, Object> payload = Map.of("title", "You have a new email");
            Types.Event mail = new Types.Event("email", AgentSystem.EVENT_NEW_MAIL,
                    Types.Priority.NORMAL, payload, "CEO");
            Map<String, Map<String, Object>> results = s.trigger(mail);
            Map<String, Object> r = results.get("CEO");
            assertEquals(false, r.get("accepted"));
            assertEquals(0, ceo.queueDepth());
        } finally {
            s.timeManager.stop();
        }
    }
}
