package com.agent.software.runtime;

import com.agent.software.domain.AgentState;
import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.domain.Task;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.LlmPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleCoordinatorTest {

    private static RoleSpec spec(String id) {
        return new RoleSpec(RoleId.of(id), "Name " + id, id, 1101, "Title", "", "",
                List.of(), "", "Test Group", "", "local", Payload.empty(), List.of());
    }

    private static AgentRuntime runtime(RoleSpec s) {
        return new AgentRuntime(s,
                new RuntimeFakes.FakeLlm(new LlmPort.ToolReply("ok", "", List.of(), 1)),
                new RuntimeFakes.FakeTools(), new RuntimeFakes.RecordingTrace(),
                x -> "SYSTEM", ToolLoop.Policy.defaults());
    }

    private static TeamRuntime team(String... ids) {
        TeamRuntime t = new TeamRuntime(LifecycleCoordinatorTest::runtime);
        for (String id : ids) {
            RoleSpec s = spec(id);
            t.register(s, runtime(s));
        }
        return t;
    }

    private static LifecycleCoordinator coordinator(TeamRuntime team) {
        return new LifecycleCoordinator(team, new RuntimeFakes.RecordingTrace());
    }

    @Test
    void pauseAndResumeTrackReason() {
        LifecycleCoordinator c = coordinator(team("a"));
        assertFalse(c.isPaused());
        c.pause("quota exhausted");
        assertTrue(c.isPaused());
        assertEquals("quota exhausted", c.pauseReason());
        c.resume();
        assertFalse(c.isPaused());
        assertEquals("", c.pauseReason());
    }

    @Test
    void queuedWorkMakesTheTeamBusy() {
        TeamRuntime t = team("a", "b");
        LifecycleCoordinator c = coordinator(t);
        assertTrue(c.allIdle());
        assertFalse(c.anyBusy());

        t.find(RoleId.of("a")).orElseThrow()
                .submit(Task.create(3, "pending work", "test", Payload.empty()));
        assertFalse(c.allIdle(), "a queued task must stop the clock from fast-forwarding");
    }

    @Test
    void rolloverReadyOnlyWhenEveryRoleIsOffDuty() {
        TeamRuntime t = team("a", "b");
        LifecycleCoordinator c = coordinator(t);
        assertFalse(c.rolloverReady());

        t.find(RoleId.of("a")).orElseThrow().setState(AgentState.OFF_DUTY);
        assertFalse(c.rolloverReady());
        t.find(RoleId.of("b")).orElseThrow().setState(AgentState.OFF_DUTY);
        assertTrue(c.rolloverReady());
    }

    @Test
    void emptyTeamIsNeitherIdleNorReady() {
        LifecycleCoordinator c = coordinator(new TeamRuntime(LifecycleCoordinatorTest::runtime));
        assertFalse(c.allIdle());
        assertFalse(c.rolloverReady());
        assertFalse(c.anyBusy());
    }

    @Test
    void forceWrapUpSetsIdleRolesOffDutyAndAbortsWaiters() {
        TeamRuntime t = team("a", "b");
        LifecycleCoordinator c = coordinator(t);
        AgentRuntime b = t.find(RoleId.of("b")).orElseThrow();
        t.find(RoleId.of("a")).orElseThrow().setState(AgentState.OFF_DUTY);
        b.setState(AgentState.IDLE);
        b.waits().begin("ceo");

        c.forceWrapUp();

        assertEquals(AgentState.OFF_DUTY, b.state());
        assertTrue(b.waits().await(java.time.Duration.ofMillis(10)).isPresent(),
                "the waiter must be woken with a synthetic reply");
    }

    @Test
    void onShiftStartWakesOffDutyRoles() {
        TeamRuntime t = team("a");
        LifecycleCoordinator c = coordinator(t);
        AgentRuntime a = t.find(RoleId.of("a")).orElseThrow();
        a.setState(AgentState.OFF_DUTY);
        c.onShiftStart();
        assertEquals(AgentState.IDLE, a.state());
    }

    @Test
    void onShiftEndAbortsWaiters() {
        TeamRuntime t = team("a");
        LifecycleCoordinator c = coordinator(t);
        AgentRuntime a = t.find(RoleId.of("a")).orElseThrow();
        a.waits().begin("coo");
        c.onShiftEnd();
        assertTrue(a.waits().await(java.time.Duration.ofMillis(10)).isPresent(),
                "shift end must wake a synchronously waiting role");
    }
}
