package com.agent.software.ports;

import java.util.List;
import java.util.Optional;

/** Per-role personal todo list. */
public interface TodoRepository {

    List<String> STATUSES = List.of("pending", "in_progress", "completed");

    List<TodoItem> list(String roleId, String status);

    TodoItem add(String roleId, String title, String detail);

    Optional<TodoItem> update(String roleId, String id, String status);

    boolean delete(String roleId, String id);

    /** One todo item. */
    record TodoItem(String id, String title, String detail, String status,
                    double createdAt, double updatedAt) {
        public TodoItem {
            title = title == null ? "" : title;
            detail = detail == null ? "" : detail;
            status = status == null ? "pending" : status;
        }
    }
}
