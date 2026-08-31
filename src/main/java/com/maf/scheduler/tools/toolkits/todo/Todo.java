package com.maf.scheduler.tools.toolkits.todo;

import com.maf.scheduler.role.AgentRole;
import com.maf.scheduler.store.TodoStore;
import com.maf.scheduler.tools.Toolkit;

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
        return "Todo 清单工具类: 添加/列出/更新/删除自己的待办事项";
    }

}
