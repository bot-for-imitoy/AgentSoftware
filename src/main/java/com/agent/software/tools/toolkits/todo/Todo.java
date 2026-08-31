package com.agent.software.tools.toolkits.todo;

import com.agent.software.role.AgentRole;
import com.agent.software.store.TodoStore;
import com.agent.software.tools.Toolkit;

/**
 * Todo 清单工具类 (Todo Toolkit) — 管理自己的待办事项:
 * todo_add / todo_list / todo_update / todo_delete.
 */
public class Todo extends Toolkit {

    private final TodoStore todoStore;

    public Todo(TodoStore todoStore) {
        this.todoStore = todoStore;
        addTool(new TodoAdd(todoStore));
        addTool(new TodoList(todoStore));
        addTool(new TodoUpdate(todoStore));
        addTool(new TodoDelete(todoStore));
    }

    public Todo(AgentRole agentRole) {
        this(agentRole.todoStore());
    }

    @Override
    public String getDescription(){
        return "Todo toolkit: add/list/update/delete your own todo items";
    }

}
