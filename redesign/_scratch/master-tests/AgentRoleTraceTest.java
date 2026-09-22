package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.io.StdInput;
import com.agent.software.llm.LLM;
import com.agent.software.web.ChatStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the Web trace pipeline: while a role worker executes a task through the
 * tool-calling loop, the ChatStore must receive chain-of-thought (reason), tool invocation
 * (tool, incl. arguments + result) and final output (answer) events for the Web UI.
 */
class AgentRoleTraceTest {

    /** Scripted LLM: round 1 requests the current time via a tool call, round 2 returns the final answer. */
    private static final class SequenceLlm implements LLM {
        int calls = 0;
        final boolean fail;   // true: always reply with an API-error marker (task must fail)

        SequenceLlm(boolean fail) {
            this.fail = fail;
        }

        @Override
        public ToolsResponse chatWithTools(List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools,
                                           double temperature, Integer maxTokens) {
            calls++;
            if (fail) {
                return new ToolsResponse("[API error: HTTP 500 fake]", List.of(), null);
            }
            if (calls == 1) {
                Map<String, Object> fn = Map.of("name", "get_time", "arguments", "{}");
                Map<String, Object> call = Map.of("id", "call_1", "type", "function", "function", fn);
                return new ToolsResponse("", "I need the current time first",
                        List.of(call), Map.of("total_tokens", 5));
            }
            return new ToolsResponse("It is 10:00 am now.", "compare with the shift schedule",
                    List.of(), Map.of("total_tokens", 5));
        }

        @Override
        public ChatResponse chat(String system, String user, double temperature, Integer maxTokens) {
            return new ChatResponse("", 0);
        }

        @Override
        public ChatResponse summarize(String logText, double temperature, Integer maxTokens) {
            return new ChatResponse("", 0);
        }
    }

    private AgentRole newRole(Path dataDir, LLM llm) {
        // autoToolkits=false → no computers / MCP servers; the worker still registers the talk toolkit
        AgentSystem system = new AgentSystem(dataDir,
                List.of(RoleLoader.getTemplate("backend_dev_1")), null, 1.0, false, new StdInput());
        AgentRole role = system.getRole("backend_dev_1");
        role.setLlm(llm);
        // register one plain tool so the loop has something to call
        role.addSingleTool("get_time", "Get the current time", Map.of("type", "object"),
                args -> "10:00 am", "test");
        return role;
    }

    private static List<Map<String, Object>> messagesOf(ChatStore store) {
        return store.messagesSince(0);
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
    }

    @Test
    void workerLoopRecordsReasonToolAnswer(@TempDir Path tmp) throws Exception {
        AgentRole role = newRole(tmp, new SequenceLlm(false));
        AgentSystem system = role.system();
        ChatStore store = system.chatStore;
        try {
            system.pool.start();
            AgentRole.Task task = new AgentRole.Task(3, "Check the current time and report it", "test",
                    Map.of("payload", Map.of("text", "what time is it?")));
            role.addTask(task);
            awaitTask(task);

            assertEquals(AgentRole.STATUS_DONE, task.status);
            List<Map<String, Object>> msgs = messagesOf(store);
            List<String> kinds = new ArrayList<>();
            for (Map<String, Object> m : msgs) {
                kinds.add((String) m.get("kind"));
            }
            // round1 reasoning → tool call → round2 reasoning → final answer
            assertEquals(List.of("reason", "tool", "reason", "answer"), kinds);

            Map<String, Object> reason1 = msgs.get(0);
            assertEquals("backend_dev_1", reason1.get("fromRoleId"));
            assertTrue(((String) reason1.get("text")).contains("I need the current time"));
            assertEquals(role.group, reason1.get("group"));

            @SuppressWarnings("unchecked")
            Map<String, Object> toolExtra = (Map<String, Object>) msgs.get(1).get("extra");
            assertEquals("get_time", toolExtra.get("tool"));
            String argsJson = (String) toolExtra.get("args");
            assertEquals("{}", argsJson.replaceAll("\\s+", ""));   // pretty-printed empty object
            assertTrue(((String) toolExtra.get("result")).contains("10:00 am"));

            Map<String, Object> answer = msgs.get(3);
            assertTrue(((String) answer.get("text")).contains("10:00 am"));
            @SuppressWarnings("unchecked")
            Map<String, Object> answerExtra = (Map<String, Object>) answer.get("extra");
            assertEquals(AgentRole.STATUS_DONE, answerExtra.get("status"));
            assertNotNull(answerExtra.get("taskId"));
        } finally {
            system.pool.shutdown(false);
        }
    }

    @Test
    void failedTaskRecordsFailedAnswer(@TempDir Path tmp) throws Exception {
        AgentRole role = newRole(tmp, new SequenceLlm(true));
        AgentSystem system = role.system();
        ChatStore store = system.chatStore;
        try {
            system.pool.start();
            AgentRole.Task task = new AgentRole.Task(3, "Query something", "test", Map.of());
            role.addTask(task);
            awaitTask(task);

            assertEquals(AgentRole.STATUS_FAILED, task.status);
            List<Map<String, Object>> msgs = messagesOf(store);
            assertTrue(msgs.size() >= 1);
            Map<String, Object> answer = msgs.get(msgs.size() - 1);
            assertEquals(ChatStore.KIND_ANSWER, answer.get("kind"));
            assertTrue(((String) answer.get("text")).startsWith("[ERROR]"));
            @SuppressWarnings("unchecked")
            Map<String, Object> extra = (Map<String, Object>) answer.get("extra");
            assertEquals(AgentRole.STATUS_FAILED, extra.get("status"));
        } finally {
            system.pool.shutdown(false);
        }
    }
}
