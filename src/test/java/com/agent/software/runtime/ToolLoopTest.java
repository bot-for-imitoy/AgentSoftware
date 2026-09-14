package com.agent.software.runtime;

import com.agent.software.domain.Payload;
import com.agent.software.kernel.AgentException;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ChatMessage;
import com.agent.software.ports.LlmPort;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolLoopTest {

    private static final RoleId ROLE = RoleId.of("ceo");

    @Test
    void returnsFinalAnswerWithoutTools() {
        RuntimeFakes.FakeLlm llm = new RuntimeFakes.FakeLlm(
                new LlmPort.ToolReply("all done", "", List.of(), 12));
        RuntimeFakes.FakeTools tools = new RuntimeFakes.FakeTools();
        RuntimeFakes.RecordingTrace trace = new RuntimeFakes.RecordingTrace();

        ToolLoop.Outcome outcome = new ToolLoop(llm, tools, trace, ToolLoop.Policy.defaults())
                .run(ROLE, "system", "do it");

        assertEquals("all done", outcome.answer());
        assertEquals(12, outcome.tokens());
        assertEquals(1, llm.requests.size());
        assertEquals(2, llm.requests.get(0).messages().size(), "system + user");
        assertTrue(tools.calls.isEmpty());
    }

    @Test
    void executesToolCallsAndFeedsResultsBack() {
        ToolCall call = new ToolCall("c1", "echo", Payload.of("text", "hi"));
        RuntimeFakes.FakeLlm llm = new RuntimeFakes.FakeLlm(
                new LlmPort.ToolReply("calling", "thinking", List.of(call), 5),
                new LlmPort.ToolReply("finished", "", List.of(), 7));
        RuntimeFakes.FakeTools tools = new RuntimeFakes.FakeTools()
                .on("echo", ToolResult.success("pong"));
        RuntimeFakes.RecordingTrace trace = new RuntimeFakes.RecordingTrace();

        ToolLoop.Outcome outcome = new ToolLoop(llm, tools, trace, ToolLoop.Policy.defaults())
                .run(ROLE, "system", "do it");

        assertEquals("finished", outcome.answer());
        assertEquals(12, outcome.tokens());
        assertEquals(1, tools.calls.size());
        assertEquals(1, trace.tools.size());
        assertEquals("1:echo:pong", trace.tools.get(0));
        assertEquals("1:thinking", trace.reasons.get(0));

        ChatMessage fedBack = RuntimeFakes.lastUserOrToolMessage(llm);
        assertEquals("tool", fedBack.role());
        assertEquals("pong", fedBack.content());
        assertEquals("c1", fedBack.toolCallId());
    }

    @Test
    void failedReplyRaisesPortException() {
        RuntimeFakes.FakeLlm llm = new RuntimeFakes.FakeLlm(
                new LlmPort.ToolReply(LlmPort.API_ERROR_PREFIX + " boom", "", List.of(), 0));
        ToolLoop loop = new ToolLoop(llm, new RuntimeFakes.FakeTools(),
                new RuntimeFakes.RecordingTrace(), ToolLoop.Policy.defaults());

        AgentException.PortException ex = assertThrows(AgentException.PortException.class,
                () -> loop.run(ROLE, "system", "do it"));
        assertTrue(ex.getMessage().contains("round 1"));
    }

    @Test
    void exceedingRoundBudgetFailsTheTask() {
        ToolCall call = new ToolCall("c1", "loop", Payload.empty());
        RuntimeFakes.FakeLlm llm = new RuntimeFakes.FakeLlm(
                new LlmPort.ToolReply("again", "", List.of(call), 1),
                new LlmPort.ToolReply("again", "", List.of(call), 1));
        RuntimeFakes.FakeTools tools = new RuntimeFakes.FakeTools()
                .on("loop", ToolResult.success("ok"));
        ToolLoop loop = new ToolLoop(llm, tools, new RuntimeFakes.RecordingTrace(),
                new ToolLoop.Policy(1, null));

        assertThrows(AgentException.DomainException.class, () -> loop.run(ROLE, "system", "do it"));
        assertEquals(1, tools.calls.size(), "tool must run only within the allowed round");
    }

    @Test
    void exceedingTokenBudgetFailsTheTask() {
        ToolCall call = new ToolCall("c1", "loop", Payload.empty());
        RuntimeFakes.FakeLlm llm = new RuntimeFakes.FakeLlm(
                new LlmPort.ToolReply("again", "", List.of(call), 10));
        RuntimeFakes.FakeTools tools = new RuntimeFakes.FakeTools()
                .on("loop", ToolResult.success("ok"));
        ToolLoop loop = new ToolLoop(llm, tools, new RuntimeFakes.RecordingTrace(),
                new ToolLoop.Policy(5, 5));

        AgentException.DomainException ex = assertThrows(AgentException.DomainException.class,
                () -> loop.run(ROLE, "system", "do it"));
        assertTrue(ex.getMessage().contains("token budget"));
        assertFalse(tools.calls.isEmpty());
    }
}
