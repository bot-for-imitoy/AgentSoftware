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
 * 工具结果的"额外选项"：队列里有**优先级高于 HIGH**（即 EMERGENCY）的事件时，
 * 把它附在最近那条工具结果上让模型当场看到；没有就不加这个参数。
 */
class RoleUrgentEventTest {

    private static final String MARKER = "priority above HIGH";

    @Test
    void attachesTheUrgentEventToTheNearestToolResult(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            Event urgent = Event.builder()
                    .from("COO").to("CEO").type(EventType.TALK)
                    .priority(Priority.EMERGENCY)
                    .content("EMERGENCY: the client is on the line")
                    .build();
            ScriptedLlm llm = new ScriptedLlm(ceo, urgent, 2);
            ceo.setLlm(llm);
            ceo.enqueue(new Task("system", "CEO", 0, "routine work", Priority.NORMAL));
            awaitFinished(ceo);

            assertEquals(2, llm.toolResults.size());
            String first = llm.toolResults.get(0);
            assertTrue(first.contains(MARKER), first);
            assertTrue(first.contains("EMERGENCY"), first);
            assertTrue(first.contains("TALK"), first);
            assertTrue(first.contains("from COO"), first);
            assertTrue(first.contains("the client is on the line"), first);
            assertFalse(llm.toolResults.get(1).contains(MARKER),
                    "同一个事件只附到最近的一条，别每条结果都重复: " + llm.toolResults.get(1));
        } finally {
            system.stop();
        }
    }

    /** HIGH 本身（比如下班广播）不算"高于 HIGH"，不许塞进工具结果。 */
    @Test
    void highPriorityAloneAddsNothing(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            Event high = Event.builder()
                    .from("system").to("CEO").type(EventType.SHIFT_END)
                    .priority(Priority.HIGH)
                    .content("Shift end at 2026-09-24 18:00")
                    .build();
            ScriptedLlm llm = new ScriptedLlm(ceo, high, 1);
            ceo.setLlm(llm);
            ceo.enqueue(new Task("system", "CEO", 0, "routine work", Priority.NORMAL));
            awaitFinished(ceo);

            assertEquals(1, llm.toolResults.size());
            assertFalse(llm.toolResults.get(0).contains(MARKER), llm.toolResults.get(0));
            assertFalse(llm.toolResults.get(0).contains("Shift end"), llm.toolResults.get(0));
        } finally {
            system.stop();
        }
    }

    @Test
    void emptyQueueAddsNothing(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            ScriptedLlm llm = new ScriptedLlm(ceo, null, 1);
            ceo.setLlm(llm);
            ceo.enqueue(new Task("system", "CEO", 0, "routine work", Priority.NORMAL));
            awaitFinished(ceo);

            assertEquals(1, llm.toolResults.size());
            assertFalse(llm.toolResults.get(0).contains(MARKER), llm.toolResults.get(0));
            assertFalse(llm.toolResults.get(0).contains("urgent"), llm.toolResults.get(0));
        } finally {
            system.stop();
        }
    }

    private static void awaitFinished(Role role) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && role.taskHistory(1).isEmpty()) {
            Thread.sleep(20);
        }
        assertFalse(role.taskHistory(1).isEmpty(), role.roleId + " 任务没跑完: " + role.readJournal());
    }

    /**
     * 脚本化 LLM：第一轮返回 N 个工具调用（并在返回前把"紧急事件"塞进队列，
     * 模拟"任务跑到一半来了紧急事件"），之后直接给最终答复。
     */
    private static final class ScriptedLlm extends LLM {

        private final Role role;
        private final Event urgent;
        private final int toolCallsPerRound;
        private int requests = 0;
        final List<String> toolResults = new ArrayList<>();

        ScriptedLlm(Role role, Event urgent, int toolCallsPerRound) {
            this.role = role;
            this.urgent = urgent;
            this.toolCallsPerRound = toolCallsPerRound;
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
            if (requests == 1) {
                if (urgent != null) {
                    role.enqueue(urgent);
                }
                List<Map<String, Object>> calls = new ArrayList<>();
                for (int i = 0; i < toolCallsPerRound; i++) {
                    calls.add(call("call_" + i, "get_time"));
                }
                return new Response("working", "", calls, 0);
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
