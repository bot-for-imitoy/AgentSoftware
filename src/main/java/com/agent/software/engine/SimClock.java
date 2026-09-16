package com.agent.software.engine;

import com.agent.software.model.DayTick;
import com.agent.software.model.ShiftCalendar;
import com.agent.software.model.Tick;
import com.agent.software.ports.Clock;

import java.time.LocalDate;

/**
 * 模拟时钟状态的唯一所有者（纯内存、无线程）。
 *
 * <p>推进能力只暴露给 {@link ClockDriver}；其余人通过 {@link Clock} 只读视图访问。
 * master 把时钟状态、日历换算、调度表与时钟线程全塞在 {@code TimeEventBus}。
 */
public final class SimClock implements Clock {

    private final ShiftCalendar calendar;
    private LocalDate baseDate;
    private long tick;

    public SimClock(ShiftCalendar calendar, LocalDate baseDate) {
        this.calendar = calendar;
        this.baseDate = baseDate;
        this.tick = 0;
    }

    // ── 推进（仅 ClockDriver 调用） ─────────────────────────────

    /** 前进若干 tick。 */
    public void advanceTicks(long ticks) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 跳到指定绝对 tick（快进 / 跨天）。 */
    public void jumpTo(Tick target) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 恢复到某个日历坐标（读档）。 */
    public void resetTo(DayTick position) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 设置第 1 天的日历基准日。 */
    public void setBaseDate(LocalDate date) {
        throw new UnsupportedOperationException("skeleton");
    }

    // ── Clock 只读视图 ─────────────────────────────────────────

    @Override
    public Tick now() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public DayTick nowDay() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public ShiftCalendar calendar() {
        return calendar;
    }

    @Override
    public String currentDateTime() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public String describe() {
        throw new UnsupportedOperationException("skeleton");
    }
}
