package com.agent.software.tools.builtin;

import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.ToolContext;
import com.agent.software.tools.spi.Toolkit;

import java.util.List;

/**
 * 任务视图工具包（id {@code "task_view"}），暴露工具：my_tasks（经 {@link ToolContext#tasks()} 读取本角色的待办与历史任务）。
 */
public final class TaskViewToolkit implements Toolkit {

    public TaskViewToolkit() {
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
