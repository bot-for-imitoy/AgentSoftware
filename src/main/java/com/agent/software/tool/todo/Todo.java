package com.agent.software.tool.todo;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TodoId;

/** 一条待办。 */
public record Todo(TodoId id, RoleId owner, String title, String detail, TodoStatus status) {

    public enum TodoStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED
    }
}
