package com.agent.software.core;

import com.agent.software.role.AgentRole;
import com.agent.software.store.TodoStore;
import com.agent.software.tools.toolkits.taskview.TaskView;
import com.agent.software.tools.toolkits.todo.Todo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Todo list + task list tests (the Java counterpart of the Python test_todo_taskview.py).
 */
class TodoTaskViewTest {

    @TempDir
    Path tmp;

    // ── TodoStore storage layer ───────────────────────────────

    @Test
    void testTodoStoreCrud() {
        TodoStore store = new TodoStore("tester_1", tmp.resolve("todos.json").toString());
        Map<String, Object> item = store.add("Write weekly report", "This week's summary");
        assertEquals("pending", item.get("status"));
        assertEquals("Write weekly report", item.get("title"));
        // reload (persistence takes effect)
        TodoStore store2 = new TodoStore("tester_1", tmp.resolve("todos.json").toString());
        assertEquals(1, store2.list(null).size());
        // status update
        Map<String, Object> updated = store.update((String) item.get("id"), "in_progress");
        assertEquals("in_progress", updated.get("status"));
        assertEquals("completed", store.update((String) item.get("id"), "completed").get("status"));
        // status filter
        assertEquals(0, store.list("pending").size());
        assertEquals(1, store.list("completed").size());
        // delete
        assertTrue(store.delete((String) item.get("id")));
        assertFalse(store.delete((String) item.get("id")));
        assertTrue(store.list(null).isEmpty());
    }

    @Test
    void testTodoStoreInvalidStatus() {
        TodoStore store = new TodoStore("r", tmp.resolve("t.json").toString());
        Map<String, Object> item = store.add("x", "");
        assertThrows(IllegalArgumentException.class,
                () -> store.update((String) item.get("id"), "bogus"));
    }

    // ── todo tool layer ───────────────────────────────────────

    @Test
    void testTodoToolsViaHandler() {
        AgentRole role = AgentRole.builder().name("Test").roleId("tester_1").build();
        Todo todo = new Todo(new TodoStore("tester_1", tmp.resolve("todos.json").toString()));

        assertTrue(todo.trigger("todo_add", Map.of("detail", "x")).contains("Error"));
        String r1 = todo.trigger("todo_add", Map.of("title", "Write weekly report", "detail", "This week's summary"));
        assertTrue(r1.startsWith("todo_add: Added todo [ID="));
        String tid = r1.split("ID=")[1].split("]")[0];
        String lst = todo.trigger("todo_list", Map.of());
        assertTrue(lst.contains("Write weekly report") && lst.contains("pending"));
        String r2 = todo.trigger("todo_update", Map.of("todo_id", tid, "status", "in_progress"));
        assertTrue(r2.contains("→ in_progress"));
        assertTrue(todo.trigger("todo_delete", Map.of("todo_id", tid)).contains("Todo deleted"));
        assertTrue(todo.trigger("todo_list", Map.of()).contains("(no todos"));
    }

    // ── task list tool ───────────────────────────────────────

    @Test
    void testMyTasksTool() {
        AgentRole role = AgentRole.builder().name("Test").roleId("tester_1").build();
        TaskView taskView = new TaskView(role);

        String empty = taskView.trigger("my_tasks", Map.of());
        assertTrue(empty.contains("Pending (queue 0)"));

        role.addTask(new AgentRole.Task(AgentRole.Urgency.NORMAL.value, "Task not started yet", "", new LinkedHashMap<>()));
        AgentRole.Task done = new AgentRole.Task(AgentRole.Urgency.HIGH.value, "Completed task", "", new LinkedHashMap<>());
        done.status = "done";
        done.tokensConsumed = 123;
        role.appendTaskHistory(done);
        AgentRole.Task failed = new AgentRole.Task(AgentRole.Urgency.NORMAL.value, "Failed task", "", new LinkedHashMap<>());
        failed.status = "failed";
        failed.result = "[ERROR] x";
        role.appendTaskHistory(failed);

        String out = taskView.trigger("my_tasks", Map.of());
        assertTrue(out.contains("Task not started yet"));
        assertTrue(out.contains("Completed task") && out.contains("123 tokens"));
        assertTrue(out.contains("Failed task"));

        String outPending = taskView.trigger("my_tasks", Map.of("scope", "pending"));
        assertTrue(outPending.contains("Task not started yet"));
        assertFalse(outPending.contains("Completed task"));
    }
}
