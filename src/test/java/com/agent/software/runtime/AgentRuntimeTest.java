package com.agent.software.runtime;

import com.agent.software.domain.AgentState;
import com.agent.software.domain.Payload;
import com.agent.software.domain.Priority;
import com.agent.software.domain.RoleSpec;
import com.agent.software.domain.Task;
import com.agent.software.domain.TaskStatus;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.LlmPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeTest {

    private static RoleSpec spec(String id) {
        return new RoleSpec(RoleId.of(id), "Name " + id, id, 1101, "Title", "", "",
                List.of(), "", "Test Group", "", "local", Payload.empty(), List.of("talk"));
    }

    private static AgentRuntime runtime(RoleSpec spec, RuntimeFakes.FakeLlm llm,
                                        RuntimeFakes.RecordingTrace trace) {
        return new AgentRuntime(spec, llm, new RuntimeFakes.FakeTools(), trace,
                s -> "SYSTEM " + s.id(), ToolLoop.Policy.defaults());
    }

    @Test
    void executesSubmittedTaskAndRecordsAnswer() {
        RuntimeFakes.FakeLlm llm = new RuntimeFakes.FakeLlm(
                new LlmPort.ToolReply("the result", "", List.of(), 9));
        RuntimeFakes.RecordingTrace trace = new RuntimeFakes.RecordingTrace();
        AgentRuntime rt = runtime(spec("ceo"), llm, trace);
        rt.start();
        try {
            Task task = Task.create(Priority.NORMAL.value(), "work", "test", Payload.empty());
            rt.submit(task);
            assertTrue(rt.awaitIdle(Duration.ofSeconds(3)), "runtime did not become idle");

            assertEquals(TaskStatus.DONE, task.status());
            assertEquals("the result", task.result());
            assertEquals(9, task.tokensConsumed());
            assertEquals(AgentState.IDLE, rt.state());
            assertEquals(1, rt.history(10).size());
            assertEquals("done:9:the result", trace.answers.get(0));
            assertEquals("ceo", task.assignedRoleId());
        } finally {
            rt.stop();
        }
    }

    @Test
    void offDutyHoldsNormalTaskUntilStateChanges() throws Exception {
        RuntimeFakes.FakeLlm llm = new RuntimeFakes.FakeLlm(
                new LlmPort.ToolReply("done", "", List.of(), 1));
        AgentRuntime rt = runtime(spec("tester_1"), llm, new RuntimeFakes.RecordingTrace());
        rt.setState(AgentState.OFF_DUTY);
        rt.start();
        try {
            Task task = Task.create(Priority.NORMAL.value(), "normal work", "test", Payload.empty());
            rt.submit(task);
            Thread.sleep(150);
            assertEquals(TaskStatus.PENDING, task.status(), "off-duty role must not start normal work");
            assertEquals(1, rt.queueDepth());
            assertFalse(rt.isBusy());

            rt.setState(AgentState.IDLE);
            assertTrue(rt.awaitIdle(Duration.ofSeconds(3)));
            assertEquals(TaskStatus.DONE, task.status());
        } finally {
            rt.stop();
        }
    }

    @Test
    void emergencyTaskRunsWhileOffDuty() {
        RuntimeFakes.FakeLlm llm = new RuntimeFakes.FakeLlm(
                new LlmPort.ToolReply("handled", "", List.of(), 2));
        AgentRuntime rt = runtime(spec("cto"), llm, new RuntimeFakes.RecordingTrace());
        rt.setState(AgentState.OFF_DUTY);
        rt.start();
        try {
            Task task = Task.create(Priority.EMERGENCY.value(), "prod down", "alert", Payload.empty());
            rt.submit(task);
            assertTrue(rt.awaitIdle(Duration.ofSeconds(3)));
            assertEquals(TaskStatus.DONE, task.status());
        } finally {
            rt.stop();
        }
    }

    @Test
    void failedLlmCallMarksTaskFailed() {
        RuntimeFakes.FakeLlm llm = new RuntimeFakes.FakeLlm(
                new LlmPort.ToolReply(LlmPort.API_ERROR_PREFIX + " boom", "", List.of(), 0));
        RuntimeFakes.RecordingTrace trace = new RuntimeFakes.RecordingTrace();
        AgentRuntime rt = runtime(spec("coo"), llm, trace);
        rt.start();
        try {
            Task task = Task.create(Priority.NORMAL.value(), "work", "test", Payload.empty());
            rt.submit(task);
            assertTrue(rt.awaitIdle(Duration.ofSeconds(3)));
            assertEquals(TaskStatus.FAILED, task.status());
            assertTrue(trace.answers.get(0).startsWith("failed:"));
        } finally {
            rt.stop();
        }
    }
}
