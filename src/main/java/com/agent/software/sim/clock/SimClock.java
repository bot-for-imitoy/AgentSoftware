package com.agent.software.sim.clock;

import java.time.LocalDate;
import com.agent.software.kernel.Tick;
import com.agent.software.kernel.DayTick;

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
        this.baseDate = baseDate != null ? baseDate : LocalDate.now();
        this.tick = 0;
    }

    // ── 推进（仅 ClockDriver 调用） ─────────────────────────────

    /**
     * 前进若干 tick。只允许 {@link ClockDriver} 调用：其他组件拿到的都是只读的
     * {@link Clock} 视图，从类型上就没有推进能力。
     */
    public synchronized void advanceTicks(long ticks) {
        if (ticks <= 0) {
            return;
        }
        this.tick += ticks;
    }

    /** 跳到指定绝对 tick（快进 / 跨天）。同样只允许 {@link ClockDriver} 调用。 */
    public synchronized void jumpTo(Tick target) {
        if (target == null) {
            return;
        }
        this.tick = Math.max(0L, target.value());
    }

    /** 恢复到某个日历坐标（读档）。 */
    public synchronized void resetTo(DayTick position) {
        if (position == null) {
            return;
        }
        this.tick = Math.max(0L, calendar.at(position.day(), position.tickOfDay()).value());
    }

    /** 设置第 1 天的日历基准日。 */
    public synchronized void setBaseDate(LocalDate date) {
        if (date != null) {
            this.baseDate = date;
        }
    }

    // ── Clock 只读视图 ─────────────────────────────────────────

    @Override
    public synchronized Tick now() {
        return new Tick(tick);
    }

    @Override
    public synchronized DayTick nowDay() {
        return calendar.locate(now());
    }

    @Override
    public ShiftCalendar calendar() {
        return calendar;
    }

    @Override
    public synchronized String currentDateTime() {
        DayTick today = calendar.locate(new Tick(tick));
        return baseDate.plusDays((long) today.day() - 1) + " " + calendar.clockTime(today);
    }

    @Override
    public synchronized String describe() {
        DayTick today = calendar.locate(new Tick(tick));
        // 日期 + "Day N HH:MM:SS（在岗/已下班…）"，对齐 master describe() 的信息量。
        return baseDate.plusDays((long) today.day() - 1) + " " + calendar.describe(new Tick(tick));
    }
}
