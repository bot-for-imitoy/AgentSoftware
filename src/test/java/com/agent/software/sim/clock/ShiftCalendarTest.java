package com.agent.software.sim.clock;

import com.agent.software.kernel.DayTick;
import com.agent.software.kernel.Tick;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShiftCalendar} 纯日历换算测试。
 *
 * <p>迁移自 master 的 {@code TimeManagerTest}：master 把这套算术与时钟线程、事件调度、
 * 生命周期判断全混在 920 行的 {@code TimeEventBus} 里；这里只对拍"tick ↔ 时钟文本 /
 * 班次边界 / 下一班次起点"这一半，不碰线程（见 {@link ClockDriverTest}）与调度
 * （见 {@link ScheduleTableTest}）。
 *
 * <p>默认几何：1 tick = 1 模拟秒，班次 08:00:00（tick 0）→ 18:00:00（tick 36000），
 * 一天 86400 tick。
 */
class ShiftCalendarTest {

    private static final ShiftCalendar DEFAULT = ShiftCalendar.of(1.0, 8, 18);

    private static String time(long absoluteTick) {
        return DEFAULT.clockTime(DEFAULT.locate(new Tick(absoluteTick)));
    }

    // ── 默认几何：1 tick = 1 模拟秒 ─────────────────────────────

    @Test
    void 默认几何是一秒一tick() {
        assertEquals(1.0, DEFAULT.secondsPerTick());
        assertEquals(8, DEFAULT.shiftStartHour());
        assertEquals(18, DEFAULT.shiftEndHour());
        assertEquals(36000, DEFAULT.shiftEndTick()); // 10h 的秒数 = 08:00:00 → 18:00:00
        assertEquals(86400, DEFAULT.ticksPerDay());  // 24h 的秒数
    }

    @Test
    void 每秒tick数缩放几何() {
        ShiftCalendar twoSeconds = ShiftCalendar.of(2.0, 8, 18);
        assertEquals(2.0, twoSeconds.secondsPerTick());
        assertEquals(18000, twoSeconds.shiftEndTick());
        assertEquals(43200, twoSeconds.ticksPerDay());
        assertEquals("18:00:00", twoSeconds.clockTime(twoSeconds.locate(new Tick(18000))));
    }

    @Test
    void 非正比例的日历被拒绝() {
        // 新架构把日历做成不可变 record：master 的 setSecondsPerTick 已去掉，
        // 非法比例改为在构造期直接拒绝，后续除法不会得到 Infinity / 0。
        assertThrows(IllegalArgumentException.class, () -> ShiftCalendar.of(0.0, 8, 18));
        assertThrows(IllegalArgumentException.class, () -> ShiftCalendar.of(-1.0, 8, 18));
    }

    // ── tick 运算（显式状态，与时钟线程无关） ─────────────────────

    @Test
    void tick归零落在第一天班次起点() {
        DayTick at = DEFAULT.locate(new Tick(0));
        assertEquals(new DayTick(1, 0), at);
        assertTrue(DEFAULT.withinShift(at));
        assertEquals(36000, DEFAULT.ticksUntilShiftEnd(at));
    }

    @Test
    void 派生tick运算与坐标往返() {
        assertEquals(new DayTick(1, 1), DEFAULT.locate(new Tick(1)));
        assertEquals(new DayTick(2, 0), DEFAULT.locate(new Tick(86400)));
        assertEquals(36000L, DEFAULT.at(1, 36000).value());
        assertEquals(86400L, DEFAULT.at(2, 0).value());
        assertEquals(new DayTick(2, 100), DEFAULT.locate(DEFAULT.at(2, 100)));
    }

    // ── 时钟文本锚定在 08:00 ────────────────────────────────────

    @Test
    void 时钟文本锚定在早上八点() {
        assertEquals("08:00:00", time(0));      // 班次起点
        assertEquals("18:00:00", time(36000));  // 班次结束（10h）
        assertEquals("09:00:00", time(3600));   // 1h
        assertEquals("08:00:09", time(9));      // 1 tick = 1 模拟秒
        assertEquals("14:00:00", time(21600));  // 6h
        assertEquals("00:00:00", time(57600));  // 08:00 + 16h → 跨过午夜
        assertEquals("07:59:59", time(86399));
        assertEquals("08:00:00", time(86400));  // 次日 08:00（回绕）
    }

    // ── 班次窗口判定 ────────────────────────────────────────────

    @Test
    void 班次窗口判定覆盖边界() {
        assertTrue(DEFAULT.withinShift(DEFAULT.locate(new Tick(0))));
        assertTrue(DEFAULT.withinShift(DEFAULT.locate(new Tick(35999))));
        assertFalse(DEFAULT.withinShift(DEFAULT.locate(new Tick(36000))));
        assertFalse(DEFAULT.withinShift(DEFAULT.locate(new Tick(86399))));
        assertTrue(DEFAULT.withinShift(DEFAULT.locate(new Tick(86400)))); // 次日 08:00 重新在岗
        assertEquals(1, DEFAULT.ticksUntilShiftEnd(DEFAULT.locate(new Tick(35999))));
        assertEquals(0, DEFAULT.ticksUntilShiftEnd(DEFAULT.locate(new Tick(40000))));
    }

    @Test
    void 下一班次起点永远跨天() {
        // 即使此刻正是 08:00（tickOfDay == 0），返回的也是次日 08:00：
        // master 的日循环正是靠"永远跨天"保证每天只跳一次。
        assertEquals(86400L, DEFAULT.nextShiftStart(DEFAULT.locate(new Tick(0))).value());
        assertEquals(86400L, DEFAULT.nextShiftStart(DEFAULT.locate(new Tick(36000))).value());
        assertEquals(2 * 86400L, DEFAULT.nextShiftStart(DEFAULT.locate(new Tick(86400))).value());
    }

    // ── 模拟挂钟：基准日 + 日历日期 ──────────────────────────────

    @Test
    void 日历日期随基准日推进() {
        SimClock clock = new SimClock(DEFAULT, LocalDate.of(2025, 1, 6));
        assertEquals("2025-01-06 08:00:00", clock.currentDateTime());
        clock.jumpTo(new Tick(36000));
        assertEquals("2025-01-06 18:00:00", clock.currentDateTime());
        clock.jumpTo(new Tick(60000)); // 08:00 + 16h40m → 次日 00:40:00
        assertEquals(1, clock.nowDay().day(), "tick 周期仍属于第 1 天");
        assertEquals("2025-01-07 00:40:00", clock.currentDateTime());
        clock.jumpTo(new Tick(86400));
        assertEquals(2, clock.nowDay().day());
        assertEquals("2025-01-07 08:00:00", clock.currentDateTime());
    }

    @Test
    void describe带有日历与在岗状态() {
        SimClock clock = new SimClock(DEFAULT, LocalDate.of(2025, 1, 6));
        String onDuty = clock.describe();
        assertTrue(onDuty.contains("2025-01-06"), onDuty);
        assertTrue(onDuty.contains("Day 1"), onDuty);
        assertTrue(onDuty.contains("08:00:00"), onDuty);
        assertTrue(onDuty.contains("在岗"), onDuty);

        clock.jumpTo(new Tick(36000));
        String offDuty = clock.describe();
        assertTrue(offDuty.contains("2025-01-06"), offDuty);
        assertTrue(offDuty.contains("18:00:00"), offDuty);
        assertTrue(offDuty.contains("已下班"), offDuty);
    }
}
