package com.agent.software.conversation;

import com.agent.software.AgentSystem;
import com.agent.software.core.Types;
import com.agent.software.io.StdInput;
import com.agent.software.llm.LLM;
import com.agent.software.role.AgentRole;
import com.agent.software.role.RoleLoader;
import com.agent.software.tools.toolkits.memory.Memory;
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
 * End-to-end tests of the role ↔ LLM API conversation management through the real worker path
 * (RolePool.roleLoop → AgentRole.executeWithTools): committed task exchanges must be carried into
 * the next task, tool activity must be recapped into the conversation, and the end-of-day summary
 * tool must close the day's dialogue.
 */
class ConversationEndToEndTest {

    /** One scripted LLM response: either a plain final answer or a native tool call. */
    private static final class Step {
        final String toolName;      // null → plain final answer
        final String argsJson;
        final String text;

        private Step(String toolName, String argsJson, String text) {
            this.toolName = toolName;
            this.argsJson = argsJson;
            this.text = text;
        }

        static Step answer(String text) {
            return new Step(null, null, text);
        }

        static Step tool(String name, String argsJson) {
            return new Step(name, argsJson, "");
        }
    }

    /** Scripted LLM that records every messages list it receives. */
    private static final class ScriptedLlm implements LLM {
        final List<Step> steps;
        final List<List<Map<String, Object>>> received = new ArrayList<>();
        int callIndex = 0;

        ScriptedLlm(List<Step> steps) {
            this.steps = steps;
        }

        @Override
        public ToolsResponse chatWithTools(List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools,
                                           double temperature, Integer maxTokens) {
            received.add(copy(messages));
            Step step = steps.get(Math.min(callIndex, steps.size() - 1));
            callIndex++;
            if (step.toolName != null) {
                Map<String, Object> fn = Map.of("name", step.toolName, "arguments", step.argsJson);
                Map<String, Object> call = Map.of("id", "call_" + callIndex, "type", "function", "function", fn);
                return new ToolsResponse("", "", List.of(call), Map.of("total_tokens", 3));
            }
            return new ToolsResponse(step.text, "", List.of(), Map.of("total_tokens", 3));
        }

        @Override
        public ChatResponse chat(String system, String user, double temperature, Integer maxTokens) {
            return new ChatResponse("", 0);
        }

        @Override
        public ChatResponse summarize(String logText, double temperature, Integer maxTokens) {
            return new ChatResponse("", 0);
        }

        private static List<Map<String, Object>> copy(List<Map<String, Object>> messages) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> m : messages) {
                Map<String, Object> copy = new LinkedHashMap<>();
                copy.put("role", m.get("role"));
                copy.put("content", m.get("content"));
                out.add(copy);
            }
            return out;
        }
    }

    private AgentRole newRole(Path dataDir, LLM llm) {
        // autoToolkits=false → no computers / MCP servers; the worker still registers the talk toolkit
        AgentSystem system = new AgentSystem(dataDir, null, List.of("backend_dev_1"),
                1.0, false, new StdInput());
        AgentRole role = system.getRole("backend_dev_1");
        role.setLlm(llm);
        return role;
    }

    private static void awaitTask(AgentRole.Task task) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (!AgentRole.STATUS_PENDING.equals(task.status)
                    && !AgentRole.STATUS_RUNNING.equals(task.status)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("task did not finish: " + task.status);
    }

    @Test
    void committedExchangeIsCarriedIntoNextTask(@TempDir Path tmp) throws Exception {
        ScriptedLlm llm = new ScriptedLlm(List.of(
                Step.answer("First task answer."),
                Step.answer("Second task answer.")));
        AgentRole role = newRole(tmp, llm);
        AgentSystem system = role.system();
        try {
            system.pool.start();
            AgentRole.Task task1 = new AgentRole.Task(3, "Implement login", "test", Map.of());
            AgentRole.Task task2 = new AgentRole.Task(3, "Fix review comments", "test", Map.of());
            role.addTask(task1);
            awaitTask(task1);
            role.addTask(task2);
            awaitTask(task2);

            assertEquals(AgentRole.STATUS_DONE, task2.status);
            assertEquals(2, llm.received.size());
            List<Map<String, Object>> second = llm.received.get(1);
            // system + committed [user task1, assistant answer1] + new user task
            assertEquals(4, second.size());
            assertEquals("system", second.get(0).get("role"));
            assertEquals("user", second.get(1).get("role"));
            assertEquals("Implement login", second.get(1).get("content"));
            assertEquals("assistant", second.get(2).get("role"));
            assertEquals("First task answer.", second.get(2).get("content"));
            assertEquals("user", second.get(3).get("role"));
            assertEquals("Fix review comments", second.get(3).get("content"));
        } finally {
            system.pool.shutdown(false);
        }
    }

    @Test
    void toolActivityIsRecappedIntoTheCommittedExchange(@TempDir Path tmp) throws Exception {
        ScriptedLlm llm = new ScriptedLlm(List.of(
                Step.tool("get_time", "{}"),
                Step.answer("It is 10:00 am now.")));
        AgentRole role = newRole(tmp, llm);
        AgentSystem system = role.system();
        role.addSingleTool("get_time", "Get the current time", Map.of("type", "object"),
                args -> "10:00 am", "test");
        try {
            system.pool.start();
            AgentRole.Task task = new AgentRole.Task(3, "Check the current time and report it", "test",
                    Map.of("payload", Map.of("text", "what time is it?")));
            role.addTask(task);
            awaitTask(task);
            assertEquals(AgentRole.STATUS_DONE, task.status);

            // the committed assistant message carries a compact recap of the tool outcome
            Conversation conv = role.conversation();
            assertEquals(2, conv.historySize());
            List<Map<String, Object>> history = conv.historySnapshot();
            assertEquals("assistant", history.get(1).get("role"));
            String assistant = (String) history.get(1).get("content");
            assertTrue(assistant.contains("It is 10:00 am now."));
            assertTrue(assistant.contains("get_time"));
            assertTrue(assistant.contains("10:00 am"));
        } finally {
            system.pool.shutdown(false);
        }
    }

    @Test
    void summaryToolClosesTheDayConversation(@TempDir Path tmp) throws Exception {
        ScriptedLlm llm = new ScriptedLlm(List.of(
                Step.answer("Job done."),
                Step.tool("summary", "{\"content\":\"Today I implemented the login page.\",\"day\":1}"),
                Step.answer("Summary saved.")));
        AgentRole role = newRole(tmp, llm);
        AgentSystem system = role.system();
        role.addToolkit(new Memory(role));   // registers the summary tool on the role
        try {
            system.pool.start();
            // normal daytime task → its exchange is committed to the day dialogue
            AgentRole.Task task1 = new AgentRole.Task(3, "Implement the login page", "test", Map.of());
            role.addTask(task1);
            awaitTask(task1);
            assertEquals(AgentRole.STATUS_DONE, task1.status);
            assertEquals(2, role.conversation().historySize());

            // shift-end task → the role calls summary(), which persists the recap and closes the dialogue
            AgentRole.Task task2 = new AgentRole.Task(3, "Shift end: please summarize today's work with the summary tool", "time", Map.of());
            role.addTask(task2);
            awaitTask(task2);
            assertEquals(AgentRole.STATUS_DONE, task2.status);
            assertEquals(Types.AgentState.OFF_DUTY, role.state);

            Conversation conv = role.conversation();
            assertTrue(conv.isEmpty(), "day dialogue must be cleared after the end-of-day summary");
            assertEquals(1, conv.closedDay());
            // the trailing "summary saved" exchange must not be appended on the closed day
            assertFalse(conv.appendTaskExchange(1, "Shift end", "Summary saved", llm));

            // a new day starts a fresh conversation
            assertTrue(conv.appendTaskExchange(2, "New day task", "New answer", llm));
            assertEquals(2, conv.historySize());
            assertEquals(2, conv.day());
            assertTrue(conv.closedDay() < 0);
        } finally {
            system.pool.shutdown(false);
        }
    }
}
