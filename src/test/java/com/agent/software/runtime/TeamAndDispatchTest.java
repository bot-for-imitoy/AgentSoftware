package com.agent.software.runtime;

import com.agent.software.domain.AgentState;
import com.agent.software.domain.DeliveryMode;
import com.agent.software.domain.Event;
import com.agent.software.domain.EventType;
import com.agent.software.domain.Payload;
import com.agent.software.domain.Priority;
import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.LlmPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamAndDispatchTest {

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
        TeamRuntime t = new TeamRuntime(TeamAndDispatchTest::runtime);
        for (String id : ids) {
            RoleSpec s = spec(id);
            t.register(s, runtime(s));
        }
        return t;
    }

    @Test
    void registerFindFireAndRoster() {
        TeamRuntime t = team("ceo", "coo");
        assertEquals(2, t.size());
        assertTrue(t.contains(RoleId.of("ceo")));
        assertEquals("Name ceo", t.find(RoleId.of("ceo")).orElseThrow().spec().name());
        assertEquals(2, t.roster().size());

        assertTrue(t.fire(RoleId.of("coo")));
        assertFalse(t.contains(RoleId.of("coo")));
        assertFalse(t.fire(RoleId.of("ghost")));
    }

    @Test
    void hireCreatesAndStartsAWorker() {
        TeamRuntime t = new TeamRuntime(TeamAndDispatchTest::runtime);
        AgentRuntime hired = t.hire(spec("newbie"));
        assertTrue(hired.isRunning());
        assertTrue(t.contains(RoleId.of("newbie")));
        hired.stop();
    }

    @Test
    void allIdleIsFalseForEmptyTeam() {
        TeamRuntime t = new TeamRuntime(TeamAndDispatchTest::runtime);
        assertFalse(t.allIdle());
        t.register(spec("a"), runtime(spec("a")));
        assertTrue(t.allIdle());
    }

    @Test
    void broadcastReachesEveryRegisteredRole() {
        TeamRuntime t = team("a", "b");
        Map<RoleId, DispatchService.DeliveryOutcome> out = new DispatchService(t)
                .dispatch(Event.broadcast("time", EventType.SHIFT_START, Priority.EMERGENCY, Payload.empty()));

        assertEquals(2, out.size());
        assertEquals(1, t.find(RoleId.of("a")).orElseThrow().queueDepth());
        assertEquals(1, t.find(RoleId.of("b")).orElseThrow().queueDepth());
        assertEquals(DeliveryMode.IMMEDIATE, out.get(RoleId.of("a")).mode());
    }

    @Test
    void targetedReachesOnlyTheTarget() {
        TeamRuntime t = team("a", "b");
        Map<RoleId, DispatchService.DeliveryOutcome> out = new DispatchService(t)
                .dispatch(Event.toRole("email", EventType.NEW_MAIL, Priority.NORMAL,
                        Payload.of("title", "hi"), RoleId.of("b")));

        assertEquals(1, out.size());
        assertTrue(out.containsKey(RoleId.of("b")));
        assertEquals(0, t.find(RoleId.of("a")).orElseThrow().queueDepth());
        assertEquals(1, t.find(RoleId.of("b")).orElseThrow().queueDepth());
    }

    @Test
    void offDutyRecipientIsReportedAsHeld() {
        TeamRuntime t = team("a");
        t.find(RoleId.of("a")).orElseThrow().setState(AgentState.OFF_DUTY);
        Map<RoleId, DispatchService.DeliveryOutcome> out = new DispatchService(t)
                .dispatch(Event.broadcast("x", EventType.of("work"), Priority.NORMAL, Payload.empty()));

        assertEquals(DeliveryMode.HOLD_UNTIL_SHIFT, out.get(RoleId.of("a")).mode());
        assertEquals(1, t.find(RoleId.of("a")).orElseThrow().queueDepth(), "held tasks still queue");
    }

    @Test
    void unknownTargetIsReportedWithoutFailing() {
        TeamRuntime t = team("a");
        Map<RoleId, DispatchService.DeliveryOutcome> out = new DispatchService(t)
                .dispatch(Event.toRole("x", EventType.of("work"), Priority.NORMAL,
                        Payload.empty(), RoleId.of("ghost")));

        assertNull(out.get(RoleId.of("ghost")).task());
        assertEquals("no such role", out.get(RoleId.of("ghost")).reason());
    }
}
