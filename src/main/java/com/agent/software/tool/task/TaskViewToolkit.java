package com.agent.software.tool.task;

import com.agent.software.agent.AgentTasks;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.Toolkit;

import java.util.List;

/**
 * 任务视图工具包（id {@code "task_view"}），暴露工具：my_tasks（经由构造期注入的 {@link AgentTasks} 读取本角色的待办与历史任务）。
 */
public final class TaskViewToolkit implements Toolkit {

    private final AgentTasks tasks;

    public TaskViewToolkit(AgentTasks tasks) {
        this.tasks = tasks;
    }

    @Override
    public String id() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Tool> instantiate() {
        throw new UnsupportedOperationException("skeleton");
    }
}
