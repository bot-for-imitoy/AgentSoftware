package com.agent.software.app;

import com.agent.software.adapters.input.ConsoleInputAdapter;
import com.agent.software.config.AppConfig;
import com.agent.software.config.ConfigLoader;
import com.agent.software.config.ConfigSource;
import com.agent.software.domain.AgentState;
import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.domain.Task;
import com.agent.software.domain.TaskStatus;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.LlmPort;
import com.agent.software.runtime.AgentRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationEndToEndTest {

    /** Deterministic LLM: every tool-loop round returns a final answer immediately. */
    static final class ScriptedLlm implements LlmPort {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatReply chat(ChatRequest request) {
            return new ChatReply("chat reply", "", 2);
        }

        @Override
        public ChatReply summarize(String text, int maxTokens) {
            return new ChatReply("summary", "", 1);
        }

        @Override
        public ToolReply chatWithTools(ToolRequest request) {
            calls.incrementAndGet();
            return new ToolReply("handled", "", List.of(), 3);
        }
    }

    private static AppConfig config(Path dir) throws IOException {
        Path file = dir.resolve("config.json");
        String dataDir = dir.resolve("data").toAbsolutePath().toString().replace("\\", "/");
        Files.writeString(file, "{\"storage\":{\"dataDir\":\"" + dataDir + "\"}}", StandardCharsets.UTF_8);
        return ConfigLoader.load(file, ConfigSource.empty()).toAppConfig();
    }

    private static RoleSpec spec(String id, Path dir) {
        Map<String, Object> kwargs = Map.of(
                "base_dir", dir.resolve("computers").toString(),
                "drive_dir", dir.resolve("drive").toString());
        return new RoleSpec(RoleId.of(id), "Name " + id, id, 1101, "Title", "", "",
                List.of(), "", "Leadership Group", "", "local", Payload.of(kwargs),
                List.of("time", "note", "todo"));
    }

    private static Application application(Path dir, LlmPort llm) throws IOException {
        ConsoleInputAdapter input = new ConsoleInputAdapter(
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        return Application.create(config(dir), input, llm);
    }

    private static boolean waitFor(BooleanSupplier condition, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    @Test
    void shiftStartIsDispatchedAndProcessed(@TempDir Path dir) throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        Application app = application(dir, llm);
        AgentRuntime ceo = app.team().hire(spec("ceo", dir));

        assertEquals(11, app.tools().specs(ceo.id()).size(), "time(2)+note(5)+todo(4) bound to the role");

        app.start();
        try {
            assertTrue(waitFor(() -> !ceo.history(10).isEmpty(), 3000),
                    "SHIFT_START was not turned into a completed task");
            Task task = ceo.history(10).get(0);
            assertEquals(TaskStatus.DONE, task.status());
            assertEquals("handled", task.result());
            assertTrue(llm.calls.get() >= 1);
        } finally {
            app.stop();
        }
    }

    @Test
    void twoApplicationsKeepSeparateState(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        Application a = application(dirA, new ScriptedLlm());
        Application b = application(dirB, new ScriptedLlm());
        AgentRuntime roleA = a.team().hire(spec("ceo", dirA));
        AgentRuntime roleB = b.team().hire(spec("ceo", dirB));

        assertNotSame(a.team(), b.team());
        assertNotSame(a.clock(), b.clock());
        assertNotSame(a.tools(), b.tools());
        assertNotSame(a.chatStore(), b.chatStore());

        a.notes().write("ceo", "secret", "A only");
        assertTrue(b.notes().read("ceo", "secret").isEmpty(), "notes must not leak across applications");
        a.todos().add("ceo", "task A", "");
        assertTrue(b.todos().list("ceo", null).isEmpty(), "todos must not leak across applications");

        a.start();
        try {
            assertTrue(waitFor(() -> !roleA.history(10).isEmpty(), 3000));
        } finally {
            a.stop();
        }
        assertTrue(roleB.history(10).isEmpty(), "the other application's role must not run");
    }

    @Test
    void pauseAndResumeDelegateToTheLifecycleCoordinator(@TempDir Path dir) throws Exception {
        Application app = application(dir, new ScriptedLlm());
        app.team().hire(spec("ceo", dir));

        assertFalse(app.lifecycle().isPaused());
        app.pause("quota exhausted");
        assertTrue(app.lifecycle().isPaused());
        assertEquals("quota exhausted", app.lifecycle().pauseReason());
        app.resume();
        assertFalse(app.lifecycle().isPaused());
    }

    @Test
    void savesAndRestoresRolesAndTasks(@TempDir Path dir) throws Exception {
        Application first = application(dir, new ScriptedLlm());
        AgentRuntime ceo = first.team().hire(spec("ceo", dir));
        ceo.submit(Task.create(6, "leftover work", "test", Payload.empty()));
        first.saveState();

        Application second = application(dir, new ScriptedLlm());
        assertEquals(1, second.restoreState());

        AgentRuntime restored = second.team().find(RoleId.of("ceo")).orElseThrow();
        assertEquals(AgentState.IDLE, restored.state());
        assertEquals(1, restored.pendingTasks().size());
        assertEquals("leftover work", restored.pendingTasks().get(0).description());
        assertEquals(6, restored.pendingTasks().get(0).urgency());
    }
}
