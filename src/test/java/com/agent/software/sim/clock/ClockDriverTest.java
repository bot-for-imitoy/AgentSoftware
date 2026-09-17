package com.agent.software.sim.clock;

import com.agent.software.kernel.DayTick;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.ScheduleId;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Tick;
import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.EventKind;
import com.agent.software.sim.event.EventSink;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClockDriver} 单步驱动测试。
 *
 * <p>迁移自 master 的 {@code TimeManagerTest} 里"时钟线程该做什么"那一半：忙碌推进、
 * 全员空闲快进、跨天门控（master 的 {@code dayRolloverReady}）、收尾超时兜底、
 * 到期事件投递。master 的这些判断与日历算术、调度表、生命周期全揉在
 * {@code TimeEventBus.tickLoop} 里；这里只验证"感知 → 决策 → 应用 → 投递"这条链路。
 *
 * <p>除暂停/恢复用例外，全部用 {@link ClockDriver#tickOnce()} 单步驱动，不睡真实时钟。
 */
class ClockDriverTest {

    private static final ShiftCalendar CAL = ShiftCalendar.of(1.0, 8, 18);

    private SimClock clock;
    private ScheduleTable schedule;
    private RecordingSink sink;
    private RecordingObserver observer;
    private FakeSensors sensors;

    /** 可脚本化的三布尔传感器。 */
    private static final class FakeSensors implements Sensors {
        volatile boolean busy;
        volatile boolean idle;
        volatile boolean offDuty;

        FakeSensors(boolean busy, boolean idle, boolean offDuty) {
            this.busy = busy;
            this.idle = idle;
            this.offDuty = offDuty;
        }

        @Override
        public boolean anyBusy() {
            return busy;
        }

        @Override
        public boolean allIdle() {
            return idle;
        }

        @Override
        public boolean allOffDuty() {
            return offDuty;
        }
    }

    private static final class RecordingSink implements EventSink {
        final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void publish(AgentEvent event) {
            events.add(event);
        }
    }

    private static final class RecordingObserver implements TickObserver {
        final List<Tick> seen = new ArrayList<>();

        @Override
        public void onTick(Tick now) {
            seen.add(now);
        }
    }

    private ClockDriver newDriver(ClockPolicy policy, ClockDriver.ClockOptions options) {
        clock = new SimClock(CAL, LocalDate.of(2025, 1, 6));
        schedule = new ScheduleTable(clock.calendar());
        sink = new RecordingSink();
        observer = new RecordingObserver();
        sensors = new FakeSensors(false, true, false);
        return new ClockDriver(clock, schedule, policy, sink, sensors, observer, options);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── 忙碌推进：有人在干活，时钟按真实耗时推进模拟秒 ────────────

    @Test
    void 忙碌时时钟按策略推进() {
        ClockDriver driver = newDriver(new DefaultClockPolicy(60_000L), ClockDriver.ClockOptions.defaults());
        sensors.busy = true;
        sensors.idle = false;
        assertEquals(0, clock.now().value());

        driver.tickOnce();

        assertEquals(1, clock.now().value(), "忙碌时每秒推进 1 模拟秒（1 tick）");
        assertEquals(1, observer.seen.size());
        assertEquals(0, observer.seen.get(0).value(), "观察者应先看到推进前的 tick");
    }

    // ── 全员空闲：快进到下一个触发点 ────────────────────────────

    @Test
    void 全员空闲快进到班次结束() {
        ClockDriver driver = newDriver(new DefaultClockPolicy(0L), ClockDriver.ClockOptions.defaults());
        clock.jumpTo(new Tick(CAL.shiftEndTick() / 2)); // 13:00
        driver.tickOnce();
        assertEquals(CAL.shiftEndTick(), clock.now().value(),
                "没有提醒时班次结束（18:00）是快进目标");
    }

    @Test
    void 快进到已激活提醒并在同一步投递() {
        ClockDriver driver = newDriver(new DefaultClockPolicy(0L), ClockDriver.ClockOptions.defaults());
        RoleId owner = new RoleId("ceo");
        ScheduleId id = schedule.schedule("提醒开会", owner, new DayTick(1, 50), Payload.of("title", "开会"));
        schedule.activateDay(1);

        driver.tickOnce();

        assertEquals(50, clock.now().value(), "快进目标应是提醒的触发 tick");
        assertEquals(1, sink.events.size());
        AgentEvent event = sink.events.get(0);
        assertEquals(EventKind.TASK_DUE, event.kind());
        assertEquals(owner, event.target().orElseThrow());
        assertEquals(id.value(), event.payload().stringOr("schedule_id", ""));
        assertEquals("提醒开会", event.payload().stringOr("description", ""));
    }

    // ── 跨天门控：只有离开班次且全员 OFF_DUTY 才允许跳到次日 08:00 ─

    @Test
    void 离开班次后只有全员下班才能跨天() {
        ClockDriver driver = newDriver(new DefaultClockPolicy(0L), ClockDriver.ClockOptions.defaults());
        clock.jumpTo(new Tick(CAL.shiftEndTick() + 2)); // 18:00:02
        sensors.offDuty = false;

        driver.tickOnce();
        assertEquals(CAL.shiftEndTick() + 2, clock.now().value(),
                "未全员下班时时钟必须停在 18:00 等待收尾");

        sensors.offDuty = true;
        driver.tickOnce();
        assertEquals(86400L, clock.now().value(), "全员 OFF_DUTY 后才允许跨到次日 08:00");
        assertEquals(2, clock.nowDay().day());
    }

    @Test
    void 下一个触发点候选包含提醒班次结束与跨天() {
        ClockDriver driver = newDriver(new DefaultClockPolicy(0L), ClockDriver.ClockOptions.defaults());
        ScheduleId id = schedule.schedule("提醒", new RoleId("ceo"), new DayTick(1, 10), Payload.empty());
        schedule.activateDay(1);

        assertEquals(10L, driver.nextFireTick().orElseThrow().value(), "已激活提醒优先");

        assertTrue(schedule.cancel(id));
        assertEquals(36000L, driver.nextFireTick().orElseThrow().value(), "没有提醒时班次结束是候选");

        clock.jumpTo(new Tick(CAL.shiftEndTick() + 2));
        assertTrue(driver.nextFireTick().isEmpty(), "未全员下班时没有可跨天的候选");

        sensors.offDuty = true;
        assertEquals(86400L, driver.nextFireTick().orElseThrow().value());
    }

    @Test
    void 恰好等于现在的提醒不算未来候选() {
        ClockDriver driver = newDriver(new DefaultClockPolicy(0L), ClockDriver.ClockOptions.defaults());
        schedule.schedule("提醒", new RoleId("ceo"), new DayTick(1, 50), Payload.empty());
        schedule.activateDay(1);
        clock.jumpTo(new Tick(50));

        assertEquals(36000L, driver.nextFireTick().orElseThrow().value(),
                "ClockDriver 会把 ScheduleTable 的 >= now 收紧为严格未来");
    }

    // ── 收尾超时兜底：日循环永远不会卡死 ─────────────────────────

    @Test
    void 收尾超时触发强制跨天() {
        ClockDriver driver = newDriver(new DefaultClockPolicy(0L),
                new ClockDriver.ClockOptions(5L, 5L, 0L)); // 宽限期 0ms
        clock.jumpTo(new Tick(CAL.shiftEndTick() + 2));
        sensors.offDuty = false;

        driver.tickOnce(); // 第一次观察到"离开班次"，开始宽限计时
        assertEquals(CAL.shiftEndTick() + 2, clock.now().value());

        driver.tickOnce(); // 宽限期已到 → ForceWrapUp
        assertEquals(86400L, clock.now().value());
        assertEquals(2, clock.nowDay().day());
        assertEquals(86400L, observer.seen.get(observer.seen.size() - 1).value(),
                "强制跨天后应在新班次起点再通知一次观察者");
    }

    // ── 到期提醒只触发一次 ──────────────────────────────────────

    @Test
    void 到期提醒只投递一次即使跨天() {
        ClockDriver driver = newDriver(new DefaultClockPolicy(0L), ClockDriver.ClockOptions.defaults());
        RoleId owner = new RoleId("ceo");
        schedule.schedule("一次性提醒", owner, new DayTick(1, 50), Payload.empty());
        schedule.activateDay(1);
        clock.jumpTo(new Tick(49));

        driver.tickOnce();
        assertEquals(50, clock.now().value());
        assertEquals(1, sink.events.size());

        schedule.activateDay(2); // 新班次不会让已触发的提醒复活
        clock.jumpTo(new Tick(86400 + 20));
        driver.tickOnce();
        driver.tickOnce();
        assertEquals(1, sink.events.size(), "已触发的提醒不得在新班次重新投递");
    }

    // ── 健壮性：传感器/日程表日历 ───────────────────────────────

    @Test
    void 传感器异常不会拖垮时钟() {
        Sensors broken = new Sensors() {
            @Override
            public boolean anyBusy() {
                throw new IllegalStateException("sensor down");
            }

            @Override
            public boolean allIdle() {
                throw new IllegalStateException("sensor down");
            }

            @Override
            public boolean allOffDuty() {
                throw new IllegalStateException("sensor down");
            }
        };
        SimClock brokenClock = new SimClock(CAL, LocalDate.of(2025, 1, 6));
        ClockDriver driver = new ClockDriver(brokenClock, new ScheduleTable(CAL),
                new DefaultClockPolicy(0L), new RecordingSink(), broken, new RecordingObserver(),
                ClockDriver.ClockOptions.defaults());

        assertDoesNotThrow(driver::tickOnce);
        assertEquals(0, brokenClock.now().value(), "所有传感器都失败时按 false 处理，时钟停在原地");
    }

    @Test
    void 时钟构造时把日程表对齐到同一份日历() {
        SimClock other = new SimClock(ShiftCalendar.of(2.0, 8, 18), LocalDate.of(2025, 1, 6));
        ScheduleTable mismatched = new ScheduleTable(); // 默认 1 秒/tick
        new ClockDriver(other, mismatched, new DefaultClockPolicy(0L), new RecordingSink(),
                new FakeSensors(false, true, false), new RecordingObserver(),
                ClockDriver.ClockOptions.defaults());
        assertEquals(other.calendar(), mismatched.calendar(),
                "DayTick → 绝对 tick 必须与时钟共用同一份日历");
    }

    // ── 暂停/恢复（唯一允许真实启动线程的用例） ───────────────────

    @Test
    void 暂停冻结时钟恢复后继续推进() {
        ClockDriver driver = newDriver(new DefaultClockPolicy(60_000L),
                new ClockDriver.ClockOptions(5L, 5L, 600_000L));
        sensors.busy = true;
        sensors.idle = false;
        driver.start();
        try {
            long deadline = System.currentTimeMillis() + 5_000;
            while (clock.now().value() < 20 && System.currentTimeMillis() < deadline) {
                sleep(5);
            }
            assertTrue(clock.now().value() >= 20, "忙碌时钟应当推进，tick=" + clock.now().value());

            driver.pause();
            sleep(100); // 让循环真正进入暂停分支
            assertTrue(driver.paused());
            long frozen = clock.now().value();
            sleep(300);
            assertEquals(frozen, clock.now().value(), "暂停期间时钟不得推进");

            driver.resume();
            deadline = System.currentTimeMillis() + 5_000;
            while (clock.now().value() <= frozen && System.currentTimeMillis() < deadline) {
                sleep(5);
            }
            assertTrue(clock.now().value() > frozen, "恢复后时钟必须继续推进");
        } finally {
            driver.stop();
        }
    }
}
