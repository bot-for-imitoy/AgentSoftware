package com.agent.software.tools.builtin;

import com.agent.software.domain.AgentState;
import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.domain.Task;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ComputerPort;
import com.agent.software.ports.TeamPort;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.ToolService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TalkAndTaskViewToolkitTest {

    private static final RoleId CEO = RoleId.of("CEO");
    private static final RoleId COO = RoleId.of("COO");
    private static final RoleId QA = RoleId.of("QA");

    static final class FakeTeam implements TeamPort {
        final Map<RoleId, RoleSpec> specs = new LinkedHashMap<>();
        final Map<RoleId, List<Task>> queues = new LinkedHashMap<>();
        final Map<RoleId, List<Task>> history = new LinkedHashMap<>();
        final Map<RoleId, AgentState> states = new LinkedHashMap<>();
        final Map<RoleId, String> waiting = new LinkedHashMap<>();
        final AtomicReference<String> delivered = new AtomicReference<>();

        FakeTeam add(RoleSpec spec) {
            specs.put(spec.id(), spec);
            queues.put(spec.id(), new ArrayList<>());
            history.put(spec.id(), new ArrayList<>());
            states.put(spec.id(), AgentState.IDLE);
            return this;
        }

        @Override
        public List<RoleSpec> members() {
            return List.copyOf(specs.values());
        }

        @Override
        public Optional<RoleSpec> spec(RoleId id) {
            return Optional.ofNullable(specs.get(id));
        }

        @Override
        public Optional<RoleId> resolve(String nameOrId) {
            for (Map.Entry<RoleId, RoleSpec> e : specs.entrySet()) {
                if (e.getKey().value().equalsIgnoreCase(nameOrId)
                        || e.getValue().name().equalsIgnoreCase(nameOrId)) {
                    return Optional.of(e.getKey());
                }
            }
            return Optional.empty();
        }

        @Override
        public AgentState stateOf(RoleId id) {
            return states.getOrDefault(id, AgentState.IDLE);
        }

        @Override
        public Optional<String> waitingReplyFrom(RoleId id) {
            return Optional.ofNullable(waiting.get(id));
        }

        @Override
        public boolean deliverReply(RoleId target, RoleId from, String message) {
            if (states.get(target) == AgentState.WAITING && from.value().equals(waiting.get(target))) {
                delivered.set(message);
                return true;
            }
            return false;
        }

        @Override
        public void enqueue(RoleId target, Task task) {
            queues.get(target).add(task);
        }

        @Override
        public Optional<String> waitForReply(RoleId self, RoleId target, Task task, Duration timeout) {
            queues.get(target).add(task);
            return Optional.of("reply!");
        }

        @Override
        public void setState(RoleId id, AgentState state) {
            states.put(id, state);
        }

        @Override
        public List<Task> pendingTasks(RoleId id) {
            return List.copyOf(queues.getOrDefault(id, List.of()));
        }

        @Override
        public List<Task> taskHistory(RoleId id, int limit) {
            return List.copyOf(history.getOrDefault(id, List.of()));
        }
    }

    private static RoleSpec spec(RoleId id, String name, String group) {
        return new RoleSpec(id, name, "user", 1101, "Title", "duties", "", List.of("skill"),
                "", group, "", "local", Payload.empty(), List.of("talk"));
    }

    private static ToolService service(TeamPort team, java.util.function.Function<RoleId, Optional<ComputerPort>> computers) {
        ToolService service = new ToolService();
        service.bind(CEO, List.of(TalkToolkit.create(team, computers, r -> {
        })));
        service.bind(COO, List.of(TalkToolkit.create(team, computers, r -> {
        })));
        return service;
    }

    private static ToolResult call(ToolService service, RoleId role, String tool, Map<String, Object> args) {
        return service.invoke(role, new ToolCall("c", tool, Payload.of(args)));
    }

    @Test
    void talkEnqueuesTaskForSameGroupColleague() {
        FakeTeam team = new FakeTeam().add(spec(CEO, "Lin Zong", "Leadership Group"))
                .add(spec(COO, "Chen Zong", "Leadership Group"));
        ToolService service = service(team, id -> Optional.empty());

        ToolResult result = call(service, CEO, "talk",
                Map.of("target", "Chen Zong", "message", "please review", "urgency", "HIGH"));
        assertTrue(result.ok(), result.text());
        assertEquals(1, team.pendingTasks(COO).size());
        Task task = team.pendingTasks(COO).get(0);
        assertEquals(6, task.urgency(), "HIGH maps to urgency 6");
        assertTrue(task.description().contains("please review"));
    }

    @Test
    void talkRejectsCrossGroupAndUnknownTargets() {
        FakeTeam team = new FakeTeam().add(spec(CEO, "Lin Zong", "Leadership Group"))
                .add(spec(QA, "Liu Yang", "Testing Group"));
        ToolService service = service(team, id -> Optional.empty());

        assertFalse(call(service, CEO, "talk", Map.of("target", "Liu Yang", "message", "hi")).ok());
        assertFalse(call(service, CEO, "talk", Map.of("target", "Nobody", "message", "hi")).ok());
    }

    @Test
    void talkRepliesToWaitingColleague() {
        FakeTeam team = new FakeTeam().add(spec(CEO, "Lin Zong", "Leadership Group"))
                .add(spec(COO, "Chen Zong", "Leadership Group"));
        team.states.put(COO, AgentState.WAITING);
        team.waiting.put(COO, CEO.value());
        ToolService service = service(team, id -> Optional.empty());

        ToolResult result = call(service, CEO, "talk", Map.of("target", "Chen Zong", "message", "here you go"));
        assertTrue(result.text().contains("replied to"));
        assertEquals("here you go", team.delivered.get());
        assertTrue(team.pendingTasks(COO).isEmpty(), "a direct reply must not enqueue another task");
    }

    @Test
    void talkWaitReturnsTheReplyAndDetectsDeadlock() {
        FakeTeam team = new FakeTeam().add(spec(CEO, "Lin Zong", "Leadership Group"))
                .add(spec(COO, "Chen Zong", "Leadership Group"));
        ToolService service = service(team, id -> Optional.empty());

        ToolResult waited = call(service, CEO, "talk",
                Map.of("target", "Chen Zong", "message", "ping", "wait", true));
        assertTrue(waited.text().contains("reply!"), waited.text());

        team.states.put(COO, AgentState.WAITING);
        team.waiting.put(COO, CEO.value());
        ToolResult deadlock = call(service, CEO, "talk",
                Map.of("target", "Chen Zong", "message", "ping", "wait", true));
        assertFalse(deadlock.ok());
        assertTrue(deadlock.text().contains("deadlock"));
    }

    @Test
    void talkValidatesAttachment() {
        FakeTeam team = new FakeTeam().add(spec(CEO, "Lin Zong", "Leadership Group"))
                .add(spec(COO, "Chen Zong", "Leadership Group"));
        ToolService service = service(team, id -> Optional.empty());

        ToolResult absolute = call(service, CEO, "talk",
                Map.of("target", "Chen Zong", "message", "x", "attachment", "/etc/passwd"));
        assertFalse(absolute.ok());

        ToolResult noComputer = call(service, CEO, "talk",
                Map.of("target", "Chen Zong", "message", "x", "attachment", "Public/report.md"));
        assertFalse(noComputer.ok());
        assertTrue(noComputer.text().contains("attachment"));
    }

    @Test
    void listRolesShowsTheRoster() {
        FakeTeam team = new FakeTeam().add(spec(CEO, "Lin Zong", "Leadership Group"));
        ToolService service = service(team, id -> Optional.empty());
        ToolResult result = call(service, CEO, "list_roles", Map.of());
        assertTrue(result.text().contains("Lin Zong"));
        assertTrue(result.text().contains("Leadership Group"));
    }

    @Test
    void myTasksShowsPendingAndHistory() {
        FakeTeam team = new FakeTeam().add(spec(CEO, "Lin Zong", "Leadership Group"));
        team.queues.get(CEO).add(Task.create(3, "pending thing", "test", Payload.empty()));
        Task done = Task.create(3, "finished thing", "test", Payload.empty());
        done.markDone("ok", 5);
        team.history.get(CEO).add(done);

        ToolService service = new ToolService();
        service.bind(CEO, List.of(TaskViewToolkit.create(team)));

        ToolResult result = call(service, CEO, "my_tasks", Map.of());
        assertTrue(result.text().contains("pending thing"));
        assertTrue(result.text().contains("finished thing"));
        assertTrue(result.text().contains("Pending"));
    }
}
