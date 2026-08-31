package com.agent.software.core;

import com.agent.software.role.AgentRole;
import com.agent.software.role.ToolRegistry;
import com.agent.software.store.TodoStore;
import com.agent.software.tools.ToolkitBridge;
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
 * Todo 清单 + 任务列表测试 (Python 版 test_todo_taskview.py 的 Java 对应物).
 */
class TodoTaskViewTest {

    @TempDir
    Path tmp;

    // ── TodoStore 存储层 ─────────────────────────────────

    @Test
    void testTodoStoreCrud() {
        TodoStore store = new TodoStore("tester_1", tmp.resolve("todos.json").toString());
        Map<String, Object> item = store.add("写周报", "本周工作小结");
        assertEquals("pending", item.get("status"));
        assertEquals("写周报", item.get("title"));
        // 重新加载 (持久化生效)
        TodoStore store2 = new TodoStore("tester_1", tmp.resolve("todos.json").toString());
        assertEquals(1, store2.list(null).size());
        // 状态更新
        Map<String, Object> updated = store.update((String) item.get("id"), "in_progress");
        assertEquals("in_progress", updated.get("status"));
        assertEquals("completed", store.update((String) item.get("id"), "completed").get("status"));
        // 状态过滤
        assertEquals(0, store.list("pending").size());
        assertEquals(1, store.list("completed").size());
        // 删除
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

    // ── todo 工具层 ──────────────────────────────────────

    @Test
    void testTodoToolsViaHandler() {
        AgentRole role = AgentRole.builder().name("测试").roleId("tester_1").build();
        ToolRegistry.ToolKit tk = ToolkitBridge.toLegacy(
                new Todo(new TodoStore("tester_1", tmp.resolve("todos.json").toString())));
        ToolRegistry.ToolHandler h = tk.getTool("todo_add").handler;

        assertTrue(h.handle(Map.of("detail", "x")).contains("Error"));
        String r1 = h.handle(Map.of("title", "写周报", "detail", "本周小结"));
        assertTrue(r1.startsWith("todo_add: 已添加待办 [ID="));
        String tid = r1.split("ID=")[1].split("]")[0];
        String lst = tk.getTool("todo_list").handler.handle(Map.of());
        assertTrue(lst.contains("写周报") && lst.contains("pending"));
        String r2 = tk.getTool("todo_update").handler.handle(Map.of("todo_id", tid, "status", "in_progress"));
        assertTrue(r2.contains("→ in_progress"));
        assertTrue(tk.getTool("todo_delete").handler.handle(Map.of("todo_id", tid)).contains("已删除"));
        assertTrue(tk.getTool("todo_list").handler.handle(Map.of()).contains("(暂无待办"));
    }

    // ── 任务列表工具 ─────────────────────────────────────

    @Test
    void testMyTasksTool() {
        AgentRole role = AgentRole.builder().name("测试").roleId("tester_1").build();
        ToolRegistry.ToolKit tk = ToolkitBridge.toLegacy(new TaskView(role));
        ToolRegistry.ToolHandler myTasks = tk.getTool("my_tasks").handler;

        String empty = myTasks.handle(Map.of());
        assertTrue(empty.contains("待处理 (队列 0 个)"));

        role.addTask(new AgentRole.Task(AgentRole.Urgency.NORMAL.value, "还没开始的任务", "", new LinkedHashMap<>()));
        AgentRole.Task done = new AgentRole.Task(AgentRole.Urgency.HIGH.value, "已完成的任务", "", new LinkedHashMap<>());
        done.status = "done";
        done.tokensConsumed = 123;
        role.appendTaskHistory(done);
        AgentRole.Task failed = new AgentRole.Task(AgentRole.Urgency.NORMAL.value, "失败的任务", "", new LinkedHashMap<>());
        failed.status = "failed";
        failed.result = "[ERROR] x";
        role.appendTaskHistory(failed);

        String out = myTasks.handle(Map.of());
        assertTrue(out.contains("还没开始的任务"));
        assertTrue(out.contains("已完成的任务") && out.contains("123 tokens"));
        assertTrue(out.contains("失败的任务"));

        String outPending = myTasks.handle(Map.of("scope", "pending"));
        assertTrue(outPending.contains("还没开始的任务"));
        assertFalse(outPending.contains("已完成的任务"));
    }
}
