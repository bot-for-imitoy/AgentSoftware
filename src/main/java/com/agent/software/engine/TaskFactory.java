package com.agent.software.engine;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.model.AgentEvent;
import com.agent.software.model.Task;

/**
 * 事件 → 任务 的唯一转换点。
 *
 * <p>紧急度映射、任务描述、上下文装配都在这里；替代 master
 * {@code AgentRole.eventToTask} 与其分散的 urgency 映射。
 */
public final class TaskFactory {

    private final ScheduleTable schedule;

    public TaskFactory(ScheduleTable schedule) {
        this.schedule = schedule;
    }

    /** 由一个已通过投递决策的事件生成任务。 */
    public Task from(AgentEvent event, RoleId assignee) {
        throw new UnsupportedOperationException("skeleton");
    }
}
