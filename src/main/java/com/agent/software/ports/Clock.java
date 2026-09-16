package com.agent.software.ports;

import com.agent.software.model.DayTick;
import com.agent.software.model.ShiftCalendar;
import com.agent.software.model.Tick;

/**
 * 只读时钟视图。
 *
 * <p>给工具与 System Prompt 用；它们只能读时间，不能推进时间。推进能力只属于
 * {@code engine.ClockDriver} + {@code engine.SimClock}。
 */
public interface Clock {

    Tick now();

    DayTick nowDay();

    ShiftCalendar calendar();

    /** 例："2026-09-16 09:30:00"。 */
    String currentDateTime();

    /** 给 LLM 的一句话时间与班次描述。 */
    String describe();
}
