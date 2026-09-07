package com.agent.software;

import com.agent.software.core.Types;
import com.agent.software.event.TimeEventBus;
import com.agent.software.io.StdInput;
import com.agent.software.role.AgentRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentSystem-level tests of the simulated calendar clock:
 * idle semantics around shift end, the explicit day-rollover gate (daily wrap-up), the wrap-up
 * force hook, and the SHIFT_END handling that wakes roles stuck in a synchronous talk wait.
 */
class AgentSystemTimeTest {

    @TempDir
    Path tmp;

    /** Creates an AgentSystem (CEO + CTO templates, autoToolkits=false avoids podman/LLM). */
    private AgentSystem make(Path dir) {
        return new AgentSystem(dir, null, List.of("CEO", "CTO"), 30.0, false, new StdInput());
    }

    private static AgentRole.Task task(String desc) {
        return new AgentRole.Task(3, desc, "test", null);
    }

    // ── Idle semantics: queued work keeps the clock alive during the shift ─

    @Test
    void allRolesIdleTreatsQueuedWorkAsActivityBeforeShiftEnd() {
        AgentSystem s = make(tmp.resolve("a"));
        s.timeManager.setProgress(1, s.timeManager.shiftEndTick / 3);  // mid-shift (≈11:20:00)
        s.timeManager.start();             // applies the resume point synchronously
        try {
            assertEquals(s.timeManager.shiftEndTick / 3, s.timeManager.tickOfDay());
            assertTrue(s.allRolesIdle());  // nobody busy, queues empty
            s.assignTask("CEO", task("review the design"));
            assertFalse(s.allRolesIdle());  // queued work keeps the clock alive
        } finally {
            s.timeManager.stop();
        }
    }

    // ── After shift end: leftover queues are held for tomorrow and must not block the rollover ─

    @Test
    void afterShiftEndLeftoverQueuesDoNotBlockRollover() {
        AgentSystem s = make(tmp.resolve("b"));
        s.timeManager.setProgress(1, s.timeManager.shiftEndTick);  // shift ended (18:00:00)
        s.timeManager.start();
        try {
            for (AgentRole r : s.pool.allRoles()) {
                r.setState(Types.AgentState.OFF_DUTY);
            }
            s.assignTask("CEO", task("leftover task carried over to tomorrow"));
            // the leftover queue must not block the clock...
            assertTrue(s.allRolesIdle());
            // ...and once every role is OFF_DUTY the team is ready to roll to the next 08:00:00
            assertTrue(s.dayRolloverReady());
        } finally {
            s.timeManager.stop();
        }
    }

    // ── Day rollover gate: waits for every role's daily wrap-up, with a force fallback ─

    @Test
    void dayRolloverWaitsForTheDailyWrapUp() {
        AgentSystem s = make(tmp.resolve("c"));
        s.timeManager.setProgress(1, s.timeManager.shiftEndTick);
        s.timeManager.start();
        try {
            AgentRole ceo = s.getRole("CEO");
            AgentRole cto = s.getRole("CTO");
            ceo.setState(Types.AgentState.OFF_DUTY);
            cto.setState(Types.AgentState.OFF_DUTY);
            assertTrue(s.dayRolloverReady());
            cto.setState(Types.AgentState.ON_DUTY_IDLE);  // CTO never finished its summary
            assertFalse(s.dayRolloverReady());
            s.forceWrapUp();  // the time thread calls this when the wrap-up grace period expires
            assertEquals(Types.AgentState.OFF_DUTY, cto.state);
            assertTrue(s.dayRolloverReady());
        } finally {
            s.timeManager.stop();
        }
    }

    // ── SHIFT_END: roles stuck in a synchronous talk wait are woken so the wrap-up can complete ─

    @Test
    void shiftEndAbortsWaitersSoTheWrapUpCanComplete() {
        AgentSystem s = make(tmp.resolve("d"));
        AgentRole cto = s.getRole("CTO");
        cto.beginWait("CEO");
        try {
            assertTrue(cto.isWaiting());
            s.onTimeEvent(new Types.Event("time", TimeEventBus.EVENT_SHIFT_END,
                    Types.Priority.EMERGENCY, Map.of(), null));
            assertNotNull(cto.debugReplyBox());  // a wake-up message was delivered into the reply mailbox
        } finally {
            cto.endWait();
            s.timeManager.stop();
        }
    }
}
