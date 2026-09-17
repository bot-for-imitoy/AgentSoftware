package com.agent.software.tool.todo;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TodoId;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolkit;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.agent.software.tool.todo.Todo.TodoStatus;

/**
 * 待办工具包（id {@code "todo"}），暴露工具：todo_add / todo_list / todo_update / todo_delete。
 *
 * <p>所有工具都以调用者角色身份读写自己的待办，参数经 {@link JsonSchema} 强声明。
 */
public final class TodoToolkit implements Toolkit {

    private final TodoList todos;

    public TodoToolkit(TodoList todos) {
        this.todos = todos;
    }

    @Override
    public String id() {
        return "todo";
    }

    @Override
    public List<Tool> instantiate() {
        return List.of(new TodoAdd(), new TodoListTool(), new TodoUpdate(), new TodoDelete());
    }

    // ── todo_add ───────────────────────────────────────────────────

    private final class TodoAdd implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("todo_add", "给自己新增一条待办（初始状态 PENDING），返回待办 id。",
                    JsonSchema.object()
                            .string("title", "待办标题")
                            .string("detail", "（可选）待办详情")
                            .required("title"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String title = arg(arguments, "title");
            if (Text.isBlank(title)) {
                return ToolResult.error("todo_add: 缺少待办标题（title）");
            }
            String detail = arg(arguments, "detail");
            try {
                Todo todo = todos.add(agent, title, detail);
                return ToolResult.ok("todo_add: 已添加待办 [ID=" + todo.id().value() + "]：" + todo.title()
                        + "（状态 PENDING）");
            } catch (RuntimeException e) {
                return ToolResult.error("todo_add: 添加失败: " + e.getMessage());
            }
        }
    }

    // ── todo_list ──────────────────────────────────────────────────

    private final class TodoListTool implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("todo_list", "列出自己的待办，可按状态过滤。",
                    JsonSchema.object()
                            .enumeration("status", "（可选）按状态过滤", List.of("PENDING", "IN_PROGRESS", "COMPLETED")));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String raw = arg(arguments, "status");
            TodoStatus filter = null;
            if (!raw.isEmpty()) {
                filter = parseStatus(raw);
                if (filter == null) {
                    return ToolResult.error("todo_list: status 只能是 PENDING / IN_PROGRESS / COMPLETED，收到：" + raw);
                }
            }
            List<Todo> items = todos.list(agent, filter);
            if (items.isEmpty()) {
                return ToolResult.ok("todo_list: 当前没有待办"
                        + (filter == null ? "" : "（状态 " + filter + "）") + "。");
            }
            StringBuilder sb = new StringBuilder("todo_list: 共 " + items.size() + " 条待办"
                    + (filter == null ? "" : "（状态 " + filter + "）") + "：");
            for (Todo item : items) {
                sb.append("\n- [").append(item.id().value()).append("] ").append(item.status()).append(": ")
                        .append(item.title());
                if (!Text.isBlank(item.detail())) {
                    sb.append(" — ").append(item.detail());
                }
            }
            return ToolResult.ok(sb.toString());
        }
    }

    // ── todo_update ────────────────────────────────────────────────

    private final class TodoUpdate implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("todo_update", "把指定待办迁移到新状态（PENDING → IN_PROGRESS → COMPLETED）。",
                    JsonSchema.object()
                            .string("id", "待办 id（来自 todo_list）")
                            .enumeration("status", "新状态", List.of("PENDING", "IN_PROGRESS", "COMPLETED"))
                            .required("id", "status"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String id = arg(arguments, "id");
            String raw = arg(arguments, "status");
            if (Text.isBlank(id)) {
                return ToolResult.error("todo_update: 缺少待办 id（id）");
            }
            if (Text.isBlank(raw)) {
                return ToolResult.error("todo_update: 缺少新状态（status）");
            }
            TodoStatus status = parseStatus(raw);
            if (status == null) {
                return ToolResult.error("todo_update: status 只能是 PENDING / IN_PROGRESS / COMPLETED，收到：" + raw);
            }
            try {
                Optional<Todo> updated = todos.update(agent, new TodoId(id), status);
                if (updated.isEmpty()) {
                    return ToolResult.error("todo_update: 待办不存在：" + id);
                }
                return ToolResult.ok("todo_update: 待办 [ID=" + id + "] " + updated.get().title()
                        + " 状态已更新为 " + status + "。");
            } catch (RuntimeException e) {
                return ToolResult.error("todo_update: 更新失败: " + e.getMessage());
            }
        }
    }

    // ── todo_delete ────────────────────────────────────────────────

    private final class TodoDelete implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("todo_delete", "删除指定待办。",
                    JsonSchema.object()
                            .string("id", "待办 id（来自 todo_list）")
                            .required("id"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String id = arg(arguments, "id");
            if (Text.isBlank(id)) {
                return ToolResult.error("todo_delete: 缺少待办 id（id）");
            }
            try {
                if (!todos.delete(agent, new TodoId(id))) {
                    return ToolResult.error("todo_delete: 待办不存在：" + id);
                }
                return ToolResult.ok("todo_delete: 已删除待办：" + id);
            } catch (RuntimeException e) {
                return ToolResult.error("todo_delete: 删除失败: " + e.getMessage());
            }
        }
    }

    // ── 内部 ───────────────────────────────────────────────────────

    private static String arg(Payload arguments, String name) {
        return Text.orEmpty(arguments.stringOr(name, "")).strip();
    }

    /** 状态名大小写不敏感；无法识别返回 null。 */
    private static TodoStatus parseStatus(String raw) {
        String name = raw.trim().toUpperCase(Locale.ROOT);
        for (TodoStatus status : TodoStatus.values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        return null;
    }
}
