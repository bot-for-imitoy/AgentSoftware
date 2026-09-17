package com.agent.software.agent.dispatch;

import com.agent.software.agent.task.Task;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TaskId;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;
import com.agent.software.sim.clock.ScheduleTable;
import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.EventKind;

import java.time.Instant;

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
        Payload payload = event.payload();
        String title = firstNonBlank(payload.title(), payload.subject(), payload.text());
        if (title.isEmpty() && EventKind.TASK_DUE.equals(event.kind())) {
            title = reminderDescription(payload);
        }
        if (title.isEmpty()) {
            title = Text.truncate(String.valueOf(payload.asMap()), 100);
        }
        // 描述形状对齐 master："[source/eventType] 标题"
        String description = "[" + event.kind().wire() + "] " + title;
        Payload context = payload
                .with("event_id", event.id().value())
                .with("recipient", assignee.value());
        return new Task(TaskId.generate(), event.priority(), description, event.kind(),
                context, Instant.now(), assignee);
    }

    /** 定时提醒的描述：优先用调度表里的原始描述，其次用 payload 里的 description。 */
    private String reminderDescription(Payload payload) {
        String inline = payload.stringOr("description", "");
        if (!Text.isBlank(inline)) {
            return inline;
        }
        String scheduleId = payload.stringOr("schedule_id", "");
        if (schedule != null && !Text.isBlank(scheduleId)) {
            for (ScheduleTable.ScheduledEntry entry : schedule.list(null)) {
                if (entry.id().value().equals(scheduleId)) {
                    return entry.description();
                }
            }
        }
        return "";
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (!Text.isBlank(c)) {
                return c;
            }
        }
        return "";
    }
}
