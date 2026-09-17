package com.agent.software.sim.clock;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.ScheduleId;
import com.agent.software.kernel.Payload;
import com.agent.software.sim.event.AgentEvent;

import java.util.List;
import java.util.Optional;
import com.agent.software.kernel.Tick;
import com.agent.software.kernel.DayTick;

/**
 * 定时事件 / 任务提醒表。
 *
 * <p>对应 master {@code TimeEventBus} 的调度那半 + {@code NoteStore.scheduleReminder}。
 * 表本身不含线程：到期事件由 {@link ClockDriver} 弹出后交给 {@code EventSink}。
 */
public final class ScheduleTable implements ReminderScheduler {

    @Override
    public ScheduleId schedule(String description, RoleId owner, DayTick at, Payload payload) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public boolean cancel(ScheduleId id) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 改期（编辑提醒）。 */
    public ScheduledEntry reschedule(ScheduleId id, DayTick at) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 列出未触发项；owner 为 null 时列出全部。 */
    public List<ScheduledEntry> list(RoleId owner) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 弹出所有到期项，转成 TASK_DUE 事件。 */
    public List<AgentEvent> due(Tick now) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 下一个触发点（供时钟快进）。 */
    public Optional<Tick> nextFireTick(Tick now) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 新班次开始：把当天应触发的任务装入日程。 */
    public void activateDay(int day) {
        throw new UnsupportedOperationException("skeleton");
    }

    public record ScheduledEntry(ScheduleId id, String description, RoleId owner,
                                 DayTick at, Payload payload, boolean fired) {
    }
}
