package com.agent.software.tool.task;

import com.agent.software.agent.AgentTasks;
import com.agent.software.agent.task.Task;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TaskId;
import com.agent.software.kernel.Payload;
import com.agent.software.sim.event.EventKind;
import com.agent.software.sim.event.Priority;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskViewToolkit} 单元测试（迁移自 master {@code core/TodoTaskViewTest#testMyTasksTool}）。
 *
 * <p>旧的 {@code scope=pending}/{@code scope=history} 参数在新实现里取消了：待办队列与历史
 * 永远一起展示，只保留 {@code limit}（历史条数）。master 断言的 "N tokens" 也不再展示，
 * 见 TEST-BRIEF 报告。
 */
class TaskViewToolkitTest {

    private static final RoleId ME = new RoleId("tester_1");

    /** 只提供只读视图的假实现：pending() / history(limit)。 */
    private static final class FakeTasks implements AgentTasks {

        private final List<Task> pending = new ArrayList<>();
        private final List<Task> history = new ArrayList<>();

        void addPending(Task task) {
            pending.add(task);
        }

        void addHistory(Task task) {
            history.add(task);
        }

        @Override
        public List<Task> pending() {
            return List.copyOf(pending);
        }

        @Override
        public List<Task> history(int limit) {
            if (limit <= 0 || limit >= history.size()) {
                return List.copyOf(history);
            }
            return List.copyOf(history.subList(history.size() - limit, history.size()));
        }
    }

    private static Task task(String description, Priority urgency) {
        return new Task(TaskId.generate(), urgency, description,
                new EventKind("test", "TASK"), Payload.of("title", description), Instant.now(), ME);
    }

    private static Tool tool(TaskViewToolkit toolkit) {
        List<Tool> tools = toolkit.instantiate();
        assertEquals(1, tools.size());
        return tools.get(0);
    }

    @Test
    void 工具声明与参数schema() {
        TaskViewToolkit toolkit = new TaskViewToolkit(new FakeTasks());
        assertEquals("task_view", toolkit.id());
        Tool myTasks = tool(toolkit);
        assertEquals("my_tasks", myTasks.spec().name());
        Map<String, Object> schema = myTasks.spec().schema().toMap();
        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props.get("limit"));
        assertEquals("integer", ((Map<?, ?>) props.get("limit")).get("type"));
    }

    @Test
    void 空任务时给出明确提示() {
        ToolResult result = tool(new TaskViewToolkit(new FakeTasks())).invoke(ME, Payload.empty());
        assertFalse(result.error());
        assertTrue(result.text().contains("当前没有任务"), result.text());
    }

    @Test
    void 展示待办队列与最近历史() {
        FakeTasks tasks = new FakeTasks();
        tasks.addPending(task("Task not started yet", Priority.NORMAL));

        Task done = task("Completed task", Priority.HIGH);
        done.complete("完成", 123);
        tasks.addHistory(done);

        Task failed = task("Failed task", Priority.NORMAL);
        failed.fail("[ERROR] x");
        tasks.addHistory(failed);

        ToolResult result = tool(new TaskViewToolkit(tasks)).invoke(ME, Payload.empty());
        assertFalse(result.error());
        String text = result.text();
        assertTrue(text.contains("待处理任务（1）"), text);
        assertTrue(text.contains("Task not started yet"), text);
        assertTrue(text.contains("最近任务（2）"), text);
        assertTrue(text.contains("Completed task"), text);
        assertTrue(text.contains("DONE/HIGH"), text);
        assertTrue(text.contains("Failed task"), text);
        assertTrue(text.contains("FAILED/NORMAL"), text);
    }

    @Test
    void limit限制历史条数() {
        FakeTasks tasks = new FakeTasks();
        for (int i = 0; i < 3; i++) {
            Task t = task("历史任务-" + i, Priority.NORMAL);
            t.complete("ok", 1);
            tasks.addHistory(t);
        }

        ToolResult limited = tool(new TaskViewToolkit(tasks))
                .invoke(ME, Payload.of("limit", 2));
        assertTrue(limited.text().contains("历史任务-2"), limited.text());
        assertTrue(limited.text().contains("历史任务-1"), limited.text());
        assertFalse(limited.text().contains("历史任务-0"), "limit=2 只应保留最近 2 条");

        ToolResult all = tool(new TaskViewToolkit(tasks)).invoke(ME, Payload.of("limit", 0));
        assertTrue(all.text().contains("历史任务-0"), all.text());
    }

    @Test
    void 不再接受scope参数但忽略未知参数() {
        FakeTasks tasks = new FakeTasks();
        tasks.addPending(task("Task not started yet", Priority.NORMAL));
        Task done = task("Completed task", Priority.NORMAL);
        done.complete("ok", 1);
        tasks.addHistory(done);

        // scope 是新架构取消的参数；传了也不应报错，也不应改变视图
        ToolResult result = tool(new TaskViewToolkit(tasks))
                .invoke(ME, Payload.of("scope", "pending"));
        assertFalse(result.error());
        assertTrue(result.text().contains("Task not started yet"), result.text());
        assertTrue(result.text().contains("Completed task"), result.text());
    }
}
