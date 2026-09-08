package com.agent.software;

import com.agent.software.io.StdInput;
import com.agent.software.llm.LLM;
import com.agent.software.role.AgentRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentSystem pause/resume feature tests:
 * pause state transitions + frozen clock + Web chat notices, and the role-worker hold
 * (no new task starts while paused; queued work resumes after {@code resume()}).
 */
class AgentSystemPauseTest {

    /** LLM stub that fails every call instantly — used to observe task consumption without API traffic. */
    private static final class ThrowingLLM implements LLM {
        final AtomicInteger calls = new AtomicInteger();

        private void boom() {
            calls.incrementAndGet();
            throw new IllegalStateException("test LLM stub");
        }

        @Override
        public ChatResponse chat(String system, String user, double temperature, Integer maxTokens) {
            boom();
            return null;
        }

        @Override
        public ChatResponse summarize(String logText, double temperature, Integer maxTokens) {
            boom();
            return null;
        }

        @Override
        public ToolsResponse chatWithTools(List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools,
                                           double temperature, Integer maxTokens) {
            boom();
            return null;
        }
    }

    private static boolean waitUntil(long timeoutMs, BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return cond.getAsBoolean();
    }

    private static AgentRole.Task task(String desc) {
        return new AgentRole.Task(3, desc, "test", null);
    }

    // ── Pause state: transitions, frozen clock, Web chat notice, idempotency ─

    @Test
    void testPauseResumeStateAndNotices() {
        AgentSystem system = new AgentSystem();  // empty pool, no threads started
        assertFalse(system.isPaused());
        assertEquals("", system.pauseReason());

        system.pause("API reported insufficient balance/quota (HTTP 402)");
        assertTrue(system.isPaused());
        assertTrue(system.timeManager.isClockPaused());
        assertEquals("API reported insufficient balance/quota (HTTP 402)", system.pauseReason());
        // the Web chat feed received a system pause notice
        assertTrue(system.chatStore.messagesSince(0).stream()
                .anyMatch(m -> String.valueOf(m.get("text")).contains("System paused")));

        // idempotent: a repeated pause only refreshes the reason, no extra notice
        long seq = system.chatStore.lastSeq();
        system.pause("second reason");
        assertTrue(system.isPaused());
        assertEquals("second reason", system.pauseReason());
        assertEquals(seq, system.chatStore.lastSeq());

        system.resume();
        assertFalse(system.isPaused());
        assertFalse(system.timeManager.isClockPaused());
        assertEquals("", system.pauseReason());
        // the Web chat feed received the resume notice
        assertTrue(system.chatStore.messagesSince(0).stream()
                .anyMatch(m -> String.valueOf(m.get("text")).contains("System resumed")));

        // resume when not paused is a no-op (and stays running)
        system.resume();
        assertFalse(system.isPaused());
    }

    // ── Worker hold: while paused no new task is started; queued work resumes after resume ─

    @Test
    void testPausedRolesHoldTasksUntilResume(@TempDir Path tmp) throws Exception {
        AgentSystem system = new AgentSystem(tmp.resolve("pause"), null,
                List.of("CEO"), 30.0, false, new StdInput());
        AgentRole ceo = system.getRole("CEO");
        ThrowingLLM stub = new ThrowingLLM();
        ceo.setLlm(stub);          // no real API traffic in the test
        system.pool.start();
        try {
            // running: a queued task is executed (fails fast on the stub)
            int callsBefore = stub.calls.get();
            system.assignTask("CEO", task("task 1 while running"));
            assertTrue(waitUntil(5000, () -> stub.calls.get() > callsBefore),
                    "task should be executed while the system runs");
            assertEquals(0, ceo.queueDepth());
            int callsRunning = stub.calls.get();
            assertTrue(callsRunning > 0, "the running worker should have executed the task");

            // pause, wait until the worker is certainly holding, then enqueue more work
            system.pause("test pause");
            Thread.sleep(500);
            system.assignTask("CEO", task("task 2 while paused"));
            Thread.sleep(600);
            assertEquals(1, ceo.queueDepth(), "no task may start while paused");
            assertEquals(callsRunning, stub.calls.get(), "no LLM call while paused");

            // resume: the held task is processed again
            system.resume();
            assertTrue(waitUntil(5000, () -> stub.calls.get() > callsRunning),
                    "the held task should be executed after resume");
            assertEquals(0, ceo.queueDepth());
        } finally {
            system.stop();
        }
    }
}
