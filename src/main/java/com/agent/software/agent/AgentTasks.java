package com.agent.software.agent;

import com.agent.software.agent.task.Task;

import java.util.List;

/**
 * 只读的任务视图（{@code my_tasks} 工具用）。
 *
 * <p>agent 自己实现这个端口；工具不需要拿到运行时对象。
 *
 * <p>{@link #beginTask(Task)} / {@link #finishTask(Task)} 是给
 * {@link com.agent.software.agent.task.TaskRunner} 的执行回调：它们**只改变角色自身**，
 * 因此不影响"工具只读"的定位（工具侧若用假实现，保持默认空实现即可）。
 */
public interface AgentTasks {

    List<Task> pending();

    /** 最近 limit 条已结束任务；limit<=0 表示全部。 */
    List<Task> history(int limit);

    /** 执行器回调：开始执行某任务（进入忙碌态、记录当前任务）。 */
    default void beginTask(Task task) {
    }

    /** 执行器回调：任务结束（写入历史、清空当前任务、回到空闲）。 */
    default void finishTask(Task task) {
    }
}
