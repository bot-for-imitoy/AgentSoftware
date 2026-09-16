package com.agent.software.tools.builtin;

import com.agent.software.ports.TodoList;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.ToolContext;
import com.agent.software.tools.spi.Toolkit;

import java.util.List;

/**
 * 待办工具包（id {@code "todo"}），暴露工具：todo_add / todo_list / todo_update / todo_delete。
 */
public final class TodoToolkit implements Toolkit {

    private final TodoList todos;

    public TodoToolkit(TodoList todos) {
        this.todos = todos;
    }

    @Override
    public String id() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Tool> instantiate(ToolContext context) {
        throw new UnsupportedOperationException("skeleton");
    }
}
