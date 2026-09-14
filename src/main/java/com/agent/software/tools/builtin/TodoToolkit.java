package com.agent.software.tools.builtin;

import com.agent.software.kernel.JsonSchema;
import com.agent.software.ports.TodoRepository;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.Toolkit;
import com.agent.software.tools.spi.Tools;

import java.util.List;
import java.util.Optional;

/** The {@code todo} toolkit: add/list/update/delete a role's personal todo list. */
public final class TodoToolkit {

    private TodoToolkit() {
    }

    public static Toolkit create(TodoRepository repo) {
        Tool add = Tools.of("todo_add", "Add a personal todo item",
                JsonSchema.builder()
                        .required("title", JsonSchema.Property.string("The title of the todo item."))
                        .property("detail", JsonSchema.Property.string("(Optional) Detail of the todo item."))
                        .build(),
                (role, call) -> {
                    String title = Tools.argStripped(call, "title");
                    if (title.isEmpty()) {
                        return ToolResult.error("todo_add: Error: needs a todo title");
                    }
                    TodoRepository.TodoItem item = repo.add(role.value(), title,
                            Tools.argStripped(call, "detail"));
                    return ToolResult.success("todo_add: Added todo [ID=" + item.id() + "]: "
                            + item.title() + " (status pending)");
                });

        Tool list = Tools.of("todo_list", "List personal todo items",
                JsonSchema.builder()
                        .property("status", JsonSchema.Property.stringEnum(
                                "(Optional) Filter by status.", "pending", "in_progress", "completed"))
                        .build(),
                (role, call) -> {
                    String status = Tools.argStripped(call, "status");
                    List<TodoRepository.TodoItem> items =
                            repo.list(role.value(), status.isEmpty() ? null : status);
                    if (items.isEmpty()) {
                        return ToolResult.success("todo_list: (no todos"
                                + (status.isEmpty() ? "" : " with status=" + status) + ")");
                    }
                    StringBuilder sb = new StringBuilder("todo_list (").append(items.size())
                            .append(" items):");
                    for (TodoRepository.TodoItem item : items) {
                        sb.append("\n- [").append(item.id()).append("] ").append(item.status())
                                .append(": ").append(item.title());
                        if (!item.detail().isEmpty()) {
                            sb.append(" \u2014 ").append(item.detail());
                        }
                    }
                    return ToolResult.success(sb.toString());
                });

        Tool update = Tools.of("todo_update", "Change the status of a personal todo item",
                JsonSchema.builder()
                        .required("todo_id", JsonSchema.Property.string("The todo id (from todo_list)."))
                        .required("status", JsonSchema.Property.stringEnum(
                                "New status.", "pending", "in_progress", "completed"))
                        .build(),
                (role, call) -> {
                    String id = Tools.argStripped(call, "todo_id");
                    String status = Tools.argStripped(call, "status");
                    if (id.isEmpty()) {
                        return ToolResult.error("todo_update: Error: needs todo_id");
                    }
                    if (status.isEmpty()) {
                        return ToolResult.error("todo_update: Error: needs status");
                    }
                    Optional<TodoRepository.TodoItem> updated = repo.update(role.value(), id, status);
                    if (updated.isEmpty()) {
                        return ToolResult.error("todo_update: Todo not found: " + id);
                    }
                    return ToolResult.success("todo_update: Todo updated [ID=" + id + "]: "
                            + updated.get().title() + " \u2192 " + updated.get().status());
                });

        Tool delete = Tools.of("todo_delete", "Delete a personal todo item",
                JsonSchema.builder()
                        .required("todo_id", JsonSchema.Property.string("The todo id (from todo_list)."))
                        .build(),
                (role, call) -> {
                    String id = Tools.argStripped(call, "todo_id");
                    if (id.isEmpty()) {
                        return ToolResult.error("todo_delete: Error: needs todo_id");
                    }
                    return repo.delete(role.value(), id)
                            ? ToolResult.success("todo_delete: Todo deleted: " + id)
                            : ToolResult.error("todo_delete: Todo not found: " + id);
                });

        return new Toolkit("todo", "Todo toolkit: add/list/update/delete your own todo items",
                List.of(add, list, update, delete));
    }
}
