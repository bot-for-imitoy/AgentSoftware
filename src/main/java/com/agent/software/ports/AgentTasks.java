package com.agent.software.ports;

import com.agent.software.model.Task;

import java.util.List;

/**
 * 只读的任务视图（{@code my_tasks} 工具用）。
 *
 * <p>agent 自己实现这个端口；工具不需要拿到运行时对象。
 */
public interface AgentTasks {

    List<Task> pending();

    /** 最近 limit 条已结束任务；limit<=0 表示全部。 */
    List<Task> history(int limit);
}
