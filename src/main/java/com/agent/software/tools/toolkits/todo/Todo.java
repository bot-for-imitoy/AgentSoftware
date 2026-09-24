package com.agent.software.tools.toolkits.todo;

import com.agent.software.role.Role;
import com.agent.software.store.TodoStore;
import com.agent.software.tools.Toolkit;

/**
 * 待办工具包：todo_add / todo_list / todo_update / todo_delete + todo_group_list / todo_group_switch。
 *
 * <p>事项按**组**分桶，组内改动实时落盘（{@code TodoStore}）；`todo_group_switch` 决定"基线组"，
 * 不带 group 参数的操作都作用在它上面。
 */
public class Todo extends Toolkit {

    public Todo(TodoStore store) {
        addTool(new TodoAdd(store));
        addTool(new TodoList(store));
        addTool(new TodoUpdate(store));
        addTool(new TodoDelete(store));
        addTool(new TodoGroupList(store));
        addTool(new TodoGroupSwitch(store));
    }

    public Todo(Role role) {
        this(role == null || role.getSystem() == null ? null : role.todoStore());
    }

    @Override
    public String getDescription() {
        return "Todo: todo_add / todo_list / todo_update / todo_delete (items live in groups) + "
                + "todo_group_list / todo_group_switch (pick which group is your baseline)";
    }
}
