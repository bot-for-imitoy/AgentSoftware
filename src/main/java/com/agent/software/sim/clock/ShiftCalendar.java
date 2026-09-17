package com.agent.software.sim.clock;

import com.agent.software.kernel.Tick;
import com.agent.software.kernel.DayTick;
/**
 * 纯日历换算：tick ↔ 时钟文本、班次边界、下一班次起点。
 *
 * <p>无状态、可单测。master 中这些算术与时钟线程、事件调度、生命周期判断全混在
 * {@code TimeEventBus} 里。
 */
public record ShiftCalendar(double secondsPerTick, int shiftStartHour, int shiftEndHour) {

    public static ShiftCalendar of(double secondsPerTick, int shiftStartHour, int shiftEndHour) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 一天的总 tick 数。 */
    public int ticksPerDay() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 班次结束对应的当天 tick。 */
    public int shiftEndTick() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 绝对 tick → (第几天, 当天 tick)。 */
    public DayTick locate(Tick absolute) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** (第几天, 当天 tick) → 绝对 tick。 */
    public Tick at(int day, int tickOfDay) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 是否在上班时段内。 */
    public boolean withinShift(DayTick at) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 当天 tick → "HH:MM:SS"。 */
    public String clockTime(DayTick at) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 下一个班次起点（跨天 08:00）。 */
    public Tick nextShiftStart(DayTick at) {
        throw new UnsupportedOperationException("skeleton");
    }

    public int ticksUntilShiftEnd(DayTick at) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 给 LLM / 日志 / Web 的一句话时间描述。 */
    public String describe(Tick absolute) {
        throw new UnsupportedOperationException("skeleton");
    }
}
