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

    /** 一个工作班次（08:00→18:00）的模拟秒数。 */
    public static final int SIM_SECONDS_PER_SHIFT = 36000;
    /** 一个完整自然日（08:00→次日 08:00）的模拟秒数。 */
    public static final int SIM_SECONDS_PER_DAY = 86400;
    /** 默认一个 tick 代表的模拟秒数。 */
    public static final double DEFAULT_SECONDS_PER_TICK = 1.0;
    /** 默认班次开始小时：tick 0 显示 08:00。 */
    public static final int SHIFT_START_HOUR = 8;
    /** 默认班次结束小时：shiftEndTick 显示 18:00。 */
    public static final int SHIFT_END_HOUR = 18;

    /**
     * 参数校验放在规范构造器里，保证 {@link #of} 与直接 {@code new} 两条路径都会校验，
     * 避免有人绕过工厂造出非法比例（后续除法会得到 Infinity / 0）。
     */
    public ShiftCalendar {
        if (!(secondsPerTick > 0)) {
            throw new IllegalArgumentException("secondsPerTick 必须 > 0，实际为 " + secondsPerTick);
        }
    }

    public static ShiftCalendar of(double secondsPerTick, int shiftStartHour, int shiftEndHour) {
        return new ShiftCalendar(secondsPerTick, shiftStartHour, shiftEndHour);
    }

    /** 一天的总 tick 数。 */
    public int ticksPerDay() {
        return clampTicks((double) SIM_SECONDS_PER_DAY / secondsPerTick);
    }

    /** 班次结束对应的当天 tick。 */
    public int shiftEndTick() {
        return clampTicks((double) SIM_SECONDS_PER_SHIFT / secondsPerTick);
    }

    /** 绝对 tick → (第几天, 当天 tick)。day 从 1 开始：day 1 tick 0 = 第一个工作日 08:00。 */
    public DayTick locate(Tick absolute) {
        long perDay = ticksPerDay();
        long value = absolute.value();
        // 用 floorDiv/floorMod：绝对 tick 万一为负也不会得到 day 0 / 负的 tickOfDay。
        int day = (int) Math.floorDiv(value, perDay) + 1;
        int tickOfDay = (int) Math.floorMod(value, perDay);
        return new DayTick(day, tickOfDay);
    }

    /** (第几天, 当天 tick) → 绝对 tick。 */
    public Tick at(int day, int tickOfDay) {
        return new Tick((long) (day - 1) * ticksPerDay() + tickOfDay);
    }

    /** 是否在上班时段内。 */
    public boolean withinShift(DayTick at) {
        return at.tickOfDay() >= 0 && at.tickOfDay() < shiftEndTick();
    }

    /** 当天 tick → "HH:MM:SS"：总秒数 = shiftStartHour*3600 + tickOfDay*secondsPerTick，对 86400 取模。 */
    public String clockTime(DayTick at) {
        long total = (long) Math.floor((double) shiftStartHour * 3600.0
                + (double) at.tickOfDay() * secondsPerTick);
        int secondsOfDay = (int) Math.floorMod(total, (long) SIM_SECONDS_PER_DAY);
        return String.format("%02d:%02d:%02d",
                secondsOfDay / 3600, (secondsOfDay % 3600) / 60, secondsOfDay % 60);
    }

    /**
     * 该日历坐标相对"第 1 天日期"的日历日偏移。
     *
     * <p>班次起点 tick 0 显示 08:00，因此 {@code tickOfDay} 跨过真实午夜（08:00 + 16h）
     * 后，展示用的日历日期要多走一天——master 的 {@code testCalendarDateMath} 正是这么要求的：
     * 第 1 天 tick 60000 是 {@code 2025-01-07 00:40:00}，但"第几天"仍是 1。
     * 日期不能只看 {@code day()}，否则凌晨 00:00–08:00 的日期会退回前一天。
     */
    public int calendarDayOffset(DayTick at) {
        long totalSeconds = (long) Math.floor((double) shiftStartHour * 3600.0
                + (double) at.tickOfDay() * secondsPerTick);
        return (int) Math.floorDiv(totalSeconds, (long) SIM_SECONDS_PER_DAY);
    }

    /**
     * 下一个班次起点：永远返回 {@code at(at.day()+1, 0)}，即"次日 08:00"（跨天）。
     *
     * <p>边界语义：即使 {@code at} 本身就是某天的 08:00（tickOfDay==0），返回值仍是次日
     * 08:00 而不是它自己。因此它不能用来表达"下一个班次边界"；需要"今天还剩多久"请用
     * {@link #withinShift} / {@link #ticksUntilShiftEnd}。master 的日循环正是靠"永远跨天"
     * 来保证每天只跳一次。
     */
    public Tick nextShiftStart(DayTick at) {
        return at(at.day() + 1, 0);
    }

    public int ticksUntilShiftEnd(DayTick at) {
        return Math.max(0, shiftEndTick() - at.tickOfDay());
    }

    /** 给 LLM / 日志 / Web 的一句话时间描述。 */
    public String describe(Tick absolute) {
        DayTick at = locate(absolute);
        String time = clockTime(at);
        if (withinShift(at)) {
            long remainSeconds = (long) Math.floor(ticksUntilShiftEnd(at) * secondsPerTick);
            return String.format("Day %d %s（在岗，距下班 %s）", at.day(), time, formatDuration(remainSeconds));
        }
        return String.format("Day %d %s（已下班，等待跨天到次日 %02d:00:00）",
                at.day(), time, shiftStartHour);
    }

    /** 把模拟秒数格式化成 "XhYm"。 */
    private static String formatDuration(long seconds) {
        long safe = Math.max(0L, seconds);
        return (safe / 3600) + "h" + ((safe % 3600) / 60) + "m";
    }

    /** 换算结果做上下保护：至少 1，且不超过 int 范围（避免极端 secondsPerTick 下强转溢出）。 */
    private static int clampTicks(double raw) {
        long rounded = Math.round(raw);
        if (rounded < 1L) {
            return 1;
        }
        return rounded > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rounded;
    }
}
