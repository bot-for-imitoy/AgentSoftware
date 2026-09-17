package com.agent.software.tool.todo;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TodoId;

import java.util.List;
import java.util.Optional;
import com.agent.software.tool.todo.Todo.TodoStatus;

/** 待办清单能力。 */
public interface TodoList {

    /** filter 为 null 时返回全部。 */
    List<Todo> list(RoleId owner, TodoStatus filter);

    Todo add(RoleId owner, String title, String detail);

    Optional<Todo> update(RoleId owner, TodoId id, TodoStatus status);

    boolean delete(RoleId owner, TodoId id);
}
