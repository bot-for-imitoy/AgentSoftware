package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.event.Event;
import com.agent.software.event.EventType;
import com.agent.software.event.Priority;
import com.agent.software.event.Task;
import com.agent.software.io.WebInput;
import com.agent.software.llm.LLM;
import com.agent.software.llm.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具结果的"额外选项"：队列里**优先级 NORMAL 及以上**的待处理事件，都要附在最近那条工具结果上
 * 让模型实时看见；LOW 不算。同一事件只附一次，新来的事件再附。
 */
class RoleUrgentEventTest {

    private static final String MARKER = "events waiting in your queue";

    @Test
    void normalMailIsSurfacedToTheRunningTask(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            ScriptedLlm llm = run(ceo, 1, Map.of(1, List.of(
                    event(EventType.NEW_MAIL, Priority.NORMAL, "hr@agentsoftware.local",
                            "New mail from HR, subject: \"policy\", message_id=m1"))));

            String first = llm.toolResults.get(0);
            assertTrue(first.contains(MARKER), first);
            assertTrue(first.contains("NORMAL / NEW_MAIL"), first);
            assertTrue(first.contains("policy"), first);
            assertTrue(first.contains("already queued"), "NORMAL 只是告知，不该让人丢下手上的事: " + first);
            assertFalse(first.contains("workday is over"), first);
        } finally {
            system.stop();
        }
    }

    @Test
    void lowPriorityEventsStayOutOfTheToolResult(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            ScriptedLlm llm = run(ceo, 1, Map.of(1, List.of(
                    event(EventType.CUSTOM, Priority.LOW, "CTO", "purely informational"))));

            String first = llm.toolResults.get(0);
            assertFalse(first.contains(MARKER), first);
            assertFalse(first.contains("purely informational"), first);
        } finally {
            system.stop();
        }
    }

    @Test
    void emergencyKeepsItsOwnWording(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            ScriptedLlm llm = run(ceo, 1, Map.of(1, List.of(
                    event(EventType.TALK, Priority.EMERGENCY, "COO", "the client is on the line"))));

            String first = llm.toolResults.get(0);
            assertTrue(first.contains("EMERGENCY / TALK"), first);
            assertTrue(first.contains("deal with the EMERGENCY"), first);
        } finally {
            system.stop();
        }
    }

    @Test
    void shiftEndBringsTheWholeRitual(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            ScriptedLlm llm = run(ceo, 1, Map.of(1, List.of(
                    event(EventType.SHIFT_END, Priority.EMERGENCY, "system",
                            "Shift end at 2026-09-24 18:00"))));

            String result = llm.toolResults.get(0);
            assertTrue(result.contains("workday is over"), result);
            assertTrue(result.contains("Stop what you are doing immediately"), result);
            assertTrue(result.contains("Tidy up the current state"), result);
            assertTrue(result.contains("Plan tomorrow"), result);
            assertTrue(result.contains("daily summary"), result);
            assertTrue(result.contains("take_rest"), result);
        } finally {
            system.stop();
        }
    }

    /** 多条一起列出；同一条只附一次；下一轮新来的事件会再附一次。 */
    @Test
    void severalEventsAreListedOnceAndNewOnesShowUpLater(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            ScriptedLlm llm = run(ceo, 2, Map.of(
                    1, List.of(
                            event(EventType.NEW_MAIL, Priority.NORMAL, "hr@agentsoftware.local", "mail A"),
                            event(EventType.TASK, Priority.HIGH, "COO", "task B")),
                    2, List.of(event(EventType.TALK, Priority.NORMAL, "CTO", "talk C"))));

            String first = llm.toolResults.get(0);
            assertTrue(first.contains("mail A"), first);
            assertTrue(first.contains("task B"), first);
            assertTrue(first.contains("NORMAL / NEW_MAIL") && first.contains("HIGH / TASK"), first);

            String second = llm.toolResults.get(1);
            assertFalse(second.contains("mail A"), "同一条事件不该重复附: " + second);
            assertTrue(second.contains("talk C"), "下一轮新到的事件要能看见: " + second);
        } finally {
            system.stop();
        }
    }

    @Test
    void emptyQueueAddsNothing(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            ScriptedLlm llm = run(ceo, 1, Map.of());

            assertEquals(1, llm.toolResults.size());
            assertFalse(llm.toolResults.get(0).contains(MARKER), llm.toolResults.get(0));
            assertFalse(llm.toolResults.get(0).contains("waiting in your queue"), llm.toolResults.get(0));
        } finally {
            system.stop();
        }
    }

    // ── 脚手架 ──────────────────────────────────────────────────

    private static Event event(EventType type, Priority priority, String from, String content) {
        return Event.builder().from(from).to("CEO").type(type).priority(priority).content(content).build();
    }

    /** 让 CEO 跑一条任务：第 N 轮请求前把事件塞进它的队列，第 toolRounds 轮都发工具调用。 */
    private static ScriptedLlm run(Role role, int toolRounds, Map<Integer, List<Event>> injections)
            throws Exception {
        ScriptedLlm llm = new ScriptedLlm(role, toolRounds, injections);
        role.setLlm(llm);
        role.enqueue(new Task("system", "CEO", 0, "routine work", Priority.NORMAL));
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && role.taskHistory(1).isEmpty()) {
            Thread.sleep(20);
        }
        assertFalse(role.taskHistory(1).isEmpty(), role.roleId + " 任务没跑完: " + role.readJournal());
        assertEquals(toolRounds, llm.toolResults.size());
        return llm;
    }

    private static final class ScriptedLlm extends LLM {

        private final Role role;
        private final int toolRounds;
        private final Map<Integer, List<Event>> injections;
        private int requests = 0;
        final List<String> toolResults = new ArrayList<>();

        ScriptedLlm(Role role, int toolRounds, Map<Integer, List<Event>> injections) {
            this.role = role;
            this.toolRounds = toolRounds;
            this.injections = injections;
        }

        @Override
        public String getModel() {
            return "scripted";
        }

        @Override
        public String getEndpoint() {
            return "scripted://";
        }

        @Override
        public Response request() {
            requests++;
            for (Event e : injections.getOrDefault(requests, List.of())) {
                role.enqueue(e);
            }
            if (requests <= toolRounds) {
                return new Response("working", "", List.of(call("call_" + requests, "get_time")), 0);
            }
            return new Response("done", "", List.of(), 0);
        }

        @Override
        public void appendToolResult(String toolCallId, String name, String result) {
            toolResults.add(result);
            super.appendToolResult(toolCallId, name, result);
        }

        private static Map<String, Object> call(String id, String name) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", name);
            function.put("arguments", Map.of());
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", id);
            call.put("type", "function");
            call.put("function", function);
            return call;
        }
    }
}
