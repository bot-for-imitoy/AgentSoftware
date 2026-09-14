package com.agent.software.tools.builtin;

import com.agent.software.domain.Payload;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ClockPort;
import com.agent.software.ports.NoteRepository;
import com.agent.software.ports.TodoRepository;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.ToolService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinToolkitsTest {

    private static final RoleId ROLE = RoleId.of("ceo");

    // ── in-memory repositories ─────────────────────────────────────────

    static final class InMemoryTodoRepository implements TodoRepository {
        final List<TodoItem> items = new ArrayList<>();
        final AtomicInteger seq = new AtomicInteger();

        @Override
        public List<TodoItem> list(String roleId, String status) {
            if (status == null) {
                return new ArrayList<>(items);
            }
            return items.stream().filter(i -> i.status().equals(status)).toList();
        }

        @Override
        public TodoItem add(String roleId, String title, String detail) {
            TodoItem item = new TodoItem("t" + seq.incrementAndGet(), title, detail, "pending", 0, 0);
            items.add(item);
            return item;
        }

        @Override
        public Optional<TodoItem> update(String roleId, String id, String status) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id().equals(id)) {
                    TodoItem updated = new TodoItem(id, items.get(i).title(), items.get(i).detail(), status, 0, 1);
                    items.set(i, updated);
                    return Optional.of(updated);
                }
            }
            return Optional.empty();
        }

        @Override
        public boolean delete(String roleId, String id) {
            return items.removeIf(i -> i.id().equals(id));
        }
    }

    static final class InMemoryNoteRepository implements NoteRepository {
        final Map<String, String> notes = new LinkedHashMap<>();
        final Map<Integer, String> summaries = new LinkedHashMap<>();

        @Override
        public List<String> list(String roleId) {
            return new ArrayList<>(notes.keySet());
        }

        @Override
        public void write(String roleId, String title, String content) {
            notes.put(title, content);
        }

        @Override
        public Optional<String> read(String roleId, String title) {
            return Optional.ofNullable(notes.get(title));
        }

        @Override
        public boolean delete(String roleId, String title) {
            return notes.remove(title) != null;
        }

        @Override
        public void writeSummary(String roleId, int day, String content) {
            summaries.put(day, content);
        }

        @Override
        public Optional<String> summary(String roleId, int day) {
            return Optional.ofNullable(summaries.get(day));
        }

        @Override
        public Optional<String> latestSummary(String roleId, Integer beforeDay) {
            return summaries.entrySet().stream()
                    .filter(e -> beforeDay == null || e.getKey() < beforeDay)
                    .max(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue);
        }
    }

    static final class FakeClock implements ClockPort {
        @Override
        public int tick() {
            return 42;
        }

        @Override
        public int day() {
            return 1;
        }

        @Override
        public int tickOfDay() {
            return 42;
        }

        @Override
        public boolean isWorkingHours() {
            return true;
        }

        @Override
        public boolean isShiftEnd() {
            return false;
        }

        @Override
        public String describe() {
            return "08:00:42 Day 1";
        }

        @Override
        public OptionalInt nextEventTick() {
            return OptionalInt.empty();
        }

        @Override
        public ScheduleHandle schedule(ScheduleRequest request) {
            return new ScheduleHandle("h");
        }

        @Override
        public boolean cancel(String handleId) {
            return false;
        }
    }

    private static ToolResult call(ToolService service, String tool, Map<String, Object> args) {
        return service.invoke(ROLE, new ToolCall("c", tool, Payload.of(args)));
    }

    // ── tests ──────────────────────────────────────────────────────────

    @Test
    void todoLifecycleThroughTools() {
        ToolService service = new ToolService();
        service.bind(ROLE, List.of(TodoToolkit.create(new InMemoryTodoRepository())));

        ToolResult added = call(service, "todo_add", Map.of("title", "write tests", "detail", "unit"));
        assertTrue(added.ok());
        String id = added.text().replaceAll(".*ID=([^\\]]+)\\].*", "$1");
        assertTrue(added.text().contains("write tests"));

        ToolResult listed = call(service, "todo_list", Map.of());
        assertTrue(listed.text().contains("write tests"));

        ToolResult updated = call(service, "todo_update", Map.of("todo_id", id, "status", "completed"));
        assertTrue(updated.ok());
        assertTrue(updated.text().contains("completed"));

        assertTrue(call(service, "todo_list", Map.of("status", "completed")).text().contains("completed"));

        assertTrue(call(service, "todo_delete", Map.of("todo_id", id)).ok());
        assertTrue(call(service, "todo_list", Map.of()).text().contains("no todos"));
    }

    @Test
    void todoValidatesRequiredArguments() {
        ToolService service = new ToolService();
        service.bind(ROLE, List.of(TodoToolkit.create(new InMemoryTodoRepository())));
        assertFalse(call(service, "todo_add", Map.of()).ok());
        assertFalse(call(service, "todo_update", Map.of("todo_id", "t1")).ok());
    }

    @Test
    void noteLifecycleThroughTools() {
        ToolService service = new ToolService();
        service.bind(ROLE, List.of(NoteToolkit.create(new InMemoryNoteRepository())));

        assertTrue(call(service, "write_note", Map.of("name", "plan", "content", "step 1")).ok());
        assertEquals("step 1", call(service, "read_note", Map.of("name", "plan")).text());
        assertTrue(call(service, "list_notes", Map.of()).text().contains("plan"));

        assertTrue(call(service, "edit_note", Map.of("name", "plan", "content", "step 2")).ok());
        assertEquals("step 2", call(service, "read_note", Map.of("name", "plan")).text());

        assertTrue(call(service, "delete_note", Map.of("name", "plan")).ok());
        assertTrue(call(service, "list_notes", Map.of()).text().contains("no notes"));
    }

    @Test
    void timeToolkitReportsClockAndTriggersRestHook() {
        AtomicReference<RoleId> rested = new AtomicReference<>();
        ToolService service = new ToolService();
        service.bind(ROLE, List.of(TimeToolkit.create(new FakeClock(), rested::set)));

        ToolResult time = call(service, "get_time", Map.of());
        assertTrue(time.text().contains("08:00:42"));
        assertTrue(time.text().contains("42"));

        assertTrue(call(service, "take_rest", Map.of()).ok());
        assertEquals(ROLE, rested.get());
    }
}
