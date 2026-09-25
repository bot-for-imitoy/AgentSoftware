package com.agent.software.role;

import com.agent.software.AgentSystem;
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
 * 工具调用轮数**没有上限**（2026-09-24 起）：
 * 旧实现 20 轮就截断，任务以 {@code status=done} + 答复 {@code (no answer)} 收场 ——
 * 实测一周里有 26 个任务这样"假完成"，烧掉 4944 万 token 却什么都没产出。
 */
class UnboundedToolRoundsTest {

    private static final int ROUNDS = 25;   // 比旧的 MAX_TOOL_ROUNDS(20) 多

    @Test
    void aTaskMayUseMoreThanTwentyToolRounds(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            ScriptedLlm llm = new ScriptedLlm(ROUNDS, "get_time");
            ceo.setLlm(llm);
            ceo.enqueue(new Task("system", "CEO", 0, "long job", Priority.NORMAL));

            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && ceo.taskHistory(1).isEmpty()) {
                Thread.sleep(20);
            }
            assertFalse(ceo.taskHistory(1).isEmpty(), "任务没跑完: " + ceo.readJournal());

            Task done = ceo.taskHistory(1).get(0);
            assertEquals(Task.DONE, done.status);
            assertEquals("finished after " + ROUNDS + " rounds", done.result,
                    "超过 20 轮也要能正常收尾，而不是 (no answer)");
            assertEquals(ROUNDS + 1, llm.requests, "应当真的跑了 " + (ROUNDS + 1) + " 次请求");
            assertEquals(ROUNDS, llm.toolResults.size(), "每轮的工具结果都要回喂");
        } finally {
            system.stop();
        }
    }

    /** 连续 3 轮工具失败仍然是收尾闸门（无上限不等于会跑飞）。 */
    @Test
    void threeFailingRoundsStillStopTheTask(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            ScriptedLlm llm = new ScriptedLlm(Integer.MAX_VALUE, "no_such_tool");   // 永远失败
            ceo.setLlm(llm);
            ceo.enqueue(new Task("system", "CEO", 0, "doomed job", Priority.NORMAL));

            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && ceo.taskHistory(1).isEmpty()) {
                Thread.sleep(20);
            }
            assertFalse(ceo.taskHistory(1).isEmpty(), "任务没跑完: " + ceo.readJournal());
            assertTrue(llm.requests <= 5, "连续失败 3 轮就该收手，实际请求了 " + llm.requests + " 次");
            assertTrue(ceo.taskHistory(1).get(0).result.startsWith("tool failed"),
                    ceo.taskHistory(1).get(0).result);
        } finally {
            system.stop();
        }
    }

    /** 前 N 轮调工具，之后给出不带 tool_calls 的答复。 */
    private static final class ScriptedLlm extends LLM {

        private final int toolRounds;
        private final String toolName;
        private int requests = 0;
        final List<String> toolResults = new ArrayList<>();

        ScriptedLlm(int toolRounds, String toolName) {
            this.toolRounds = toolRounds;
            this.toolName = toolName;
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
            if (requests <= toolRounds) {
                return new Response("working", "", List.of(call("call_" + requests, toolName)), 0);
            }
            return new Response("finished after " + toolRounds + " rounds", "", List.of(), 0);
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
