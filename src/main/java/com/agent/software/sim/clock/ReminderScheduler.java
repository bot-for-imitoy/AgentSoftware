package com.agent.software.sim.clock;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.ScheduleId;
import com.agent.software.kernel.Payload;

/**
 * 定时提醒能力（笔记提醒等）。
 *
 * <p>由 {@code sim.clock.ScheduleTable} 实现；工具通过它注册提醒，不需要认识调度表全貌。
 */
public interface ReminderScheduler {

    ScheduleId schedule(String description, RoleId owner, DayTick at, Payload payload);

    boolean cancel(ScheduleId id);
}
