package com.agent.software.tools.toolkits.todo;

import com.agent.software.role.Role;
import com.agent.software.store.TodoStore;
import com.agent.software.tools.Toolkit;

/**
 * 待办工具包：todo_add / todo_list / todo_update / todo_delete。
 *
 * <p>待办是**一层平铺的清单**（没有"组"—— 组是 `task` 系列的概念）；每次改动实时落盘
 * （{@code TodoStore}）。
 */
public class Todo extends Toolkit {

    public Todo(TodoStore store) {
        addTool(new TodoAdd(store));
        addTool(new TodoList(store));
        addTool(new TodoUpdate(store));
        addTool(new TodoDelete(store));
    }

    public Todo(Role role) {
        this(role == null || role.getSystem() == null ? null : role.todoStore());
    }

    @Override
    public String getDescription() {
        return "Todo: todo_add / todo_list / todo_update / todo_delete (one flat list per role, "
                + "saved immediately)";
    }
}
