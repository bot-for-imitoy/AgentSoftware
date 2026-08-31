package com.maf.scheduler.core;

import com.maf.scheduler.event.TimeEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TimeEventBus 核心时间逻辑测试 (Python 版 test_time_manager.py 的 Java 对应物).
 */
class TimeManagerTest {

    private static final int TICKS_PER_DAY = 144;
    private static final int SHIFT_END_TICK = 60;

    private final List<TimeEventBus> buses = new ArrayList<>();

    private TimeEventBus makeBus() {
        TimeEventBus bus = new TimeEventBus();
        bus.checkInterval = 0.05;
        buses.add(bus);
        return bus;
    }

    @AfterEach
    void stopAll() {
        for (TimeEventBus b : buses) {
            try {
                b.stop();
            } catch (Exception ignored) {
            }
        }
        buses.clear();
    }

    private void jump(TimeEventBus bus, int targetTick) {
        bus.debugSetTick(targetTick);
        sleep(120);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Tick 数学 (显式状态, 不依赖时钟) ─────────────────

    @Test
    void testTickZeroAtStart() {
        TimeEventBus bus = makeBus();
        bus.start();
        bus.stop();
        assertEquals(0, bus.currentTick());
        assertEquals(1, bus.dayNumber());
        assertEquals(0, bus.tickOfDay());
    }

    @Test
    void testDerivedTickMath() {
        TimeEventBus bus = makeBus();
        bus.start();
        bus.stop();
        bus.debugSetTick(1);
        assertEquals(1, bus.currentTick());
        assertEquals(1, bus.dayNumber());
        bus.debugSetTick(144);
        assertEquals(144, bus.currentTick());
        assertEquals(2, bus.dayNumber());
        assertEquals(0, bus.tickOfDay());
    }

    @Test
    void testTickFrozenAgainstWallClock() {
        TimeEventBus bus = makeBus();
        bus.start();
        sleep(300);  // 真实时间流逝 0.3s
        assertEquals(0, bus.currentTick());  // Tick 冻结在 0
        bus.stop();
    }

    // ── 快进候选计算 ─────────────────────────────────────

    @Test
    void testScheduledEventAndTaskCandidates() {
        TimeEventBus bus = makeBus();
        bus.start();
        bus.stop();
        bus.registerEvent(ev("e1"), 50);
        TimeEventBus.ScheduledTask task = bus.scheduleTask("t", "CEO", 10, 1, null);
        assertNotNull(task);
        assertEquals(10, bus.debugNextEventTick());
        bus.cancelTask(task.taskId);
        assertEquals(50, bus.debugNextEventTick());
    }

    @Test
    void testNextDayShiftStartAfterShiftEnd() {
        TimeEventBus bus = makeBus();
        bus.start();
        bus.stop();
        bus.debugSetTick(62);
        assertEquals(TICKS_PER_DAY, bus.debugNextEventTick());
    }

    @Test
    void testShiftEndBeforeShiftEnd() {
        TimeEventBus bus = makeBus();
        bus.start();
        bus.stop();
        bus.debugSetTick(30);
        assertEquals(SHIFT_END_TICK, bus.debugNextEventTick());
    }

    // ── SHIFT_START/SHIFT_END 区间判定 + 每天只触发一次 ───

    @Test
    void testShiftStartFiresWhenWindowMissed() {
        TimeEventBus bus = makeBus();
        List<String> events = new ArrayList<>();
        bus.setEventSender(e -> events.add(e.eventType));
        bus.start();
        sleep(150);  // 等首轮检查 (tick 0 → 第 1 天 SHIFT_START)
        assertEquals(1, count(events, TimeEventBus.EVENT_SHIFT_START));
        jump(bus, 60);
        assertEquals(1, count(events, TimeEventBus.EVENT_SHIFT_END));
        // 大步跳到第 2 天上班时段 (tick 145 → day2 tod=1): 区间判定仍必须触发
        jump(bus, 145);
        assertEquals(2, count(events, TimeEventBus.EVENT_SHIFT_START));
        jump(bus, 204);  // 第 2 天下班
        assertEquals(2, count(events, TimeEventBus.EVENT_SHIFT_START));
        assertEquals(2, count(events, TimeEventBus.EVENT_SHIFT_END));
        jump(bus, 294);  // 第 3 天上班时段
        assertEquals(3, count(events, TimeEventBus.EVENT_SHIFT_START));
        assertEquals(2, count(events, TimeEventBus.EVENT_SHIFT_END));
    }

    // ── 任务 fired 去重: 跨天后不重复触发 ─────────────────

    @Test
    void testTaskFiresOnceAcrossDays() {
        TimeEventBus bus = makeBus();
        List<String> events = new ArrayList<>();
        bus.setEventSender(e -> events.add(e.eventType));
        bus.start();
        sleep(150);
        TimeEventBus.ScheduledTask task = bus.scheduleTask("提醒", "CEO", 2, 1, null);
        jump(bus, 3);  // 跳过 tick 2 → 触发一次, 标记 fired
        assertEquals(1, count(events, TimeEventBus.EVENT_TASK_DUE));
        assertTrue(task.fired);
        jump(bus, 145);  // 第 2 天上班: 不得重新注册已 fired 任务
        assertEquals(2, count(events, TimeEventBus.EVENT_SHIFT_START));
        assertEquals(1, count(events, TimeEventBus.EVENT_TASK_DUE));
    }

    // ── edit_task 防呆: 禁止改到已过去的时间 ──────────────

    @Test
    void testEditToPastRaises() {
        TimeEventBus bus = makeBus();
        bus.start();
        sleep(150);
        TimeEventBus.ScheduledTask task = bus.scheduleTask("t", "CEO", 5, 1, null);
        jump(bus, 6);  // tick 6 — 已过 tick 5
        assertThrows(IllegalArgumentException.class, () -> bus.editTask(task.taskId, null, 3, null));
        assertThrows(IllegalArgumentException.class, () -> bus.editTask(task.taskId, null, 5, 1));
        TimeEventBus.ScheduledTask updated = bus.editTask(task.taskId, null, 20, null);
        assertNotNull(updated);
        assertEquals(20, updated.targetTick);
    }

    @Test
    void testEditToPastDayRaises() {
        TimeEventBus bus = makeBus();
        bus.start();
        sleep(150);
        TimeEventBus.ScheduledTask task = bus.scheduleTask("t", "CEO", 1, 2, null);
        jump(bus, 147);  // 第 2 天已开始后, 把任务改回第 1 天 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> bus.editTask(task.taskId, null, 1, 1));
    }

    // ── 定时任务注册语义 ─────────────────────────────────

    @Test
    void testTaskRegisteredOnceOnCreation() {
        TimeEventBus bus = makeBus();
        bus.start();
        sleep(150);
        bus.scheduleTask("今天的事", "r1", 5, 1, null);
        assertEquals(1, bus.tickSchedule.size());  // 创建时已注册
        bus.debugLoadTodayTasksToBus();
        assertEquals(1, bus.tickSchedule.size());
        List<Types.Event> due = bus.checkDueEvents(999);
        assertEquals(1, due.size());
        assertEquals(TimeEventBus.EVENT_TASK_DUE, due.get(0).eventType);
    }

    @Test
    void testFutureTaskLoadedOnTargetDay() {
        TimeEventBus bus = makeBus();
        bus.start();
        sleep(150);
        TimeEventBus.ScheduledTask task = bus.scheduleTask("明天的事", "r1", 5, 2, null);
        assertTrue(task.eventId.isEmpty());  // 创建时是隔天 → 仅保存
        assertEquals(0, bus.tickSchedule.size());
        bus.debugSetTick(144);  // 快进跳到第 2 天
        bus.debugLoadTodayTasksToBus();
        assertTrue(!task.eventId.isEmpty());  // 现在补注册了
        assertEquals(1, bus.tickSchedule.size());
        bus.debugLoadTodayTasksToBus();
        assertEquals(1, bus.tickSchedule.size());
    }

    @Test
    void testDuplicateRegisterWarns() {
        TimeEventBus bus = makeBus();
        bus.start();
        sleep(150);
        TimeEventBus.ScheduledTask task = bus.scheduleTask("只注册一次", "r1", 5, 1, null);
        assertEquals(1, bus.tickSchedule.size());
        // 异常路径: 重复注册 → 不重复入表
        bus.debugRegisterTaskEventIfToday(task);
        assertEquals(1, bus.tickSchedule.size());
    }

    // ── 辅助 ──────────────────────────────────────────────

    private static Types.Event ev(String eventType) {
        return new Types.Event("test", eventType, Types.Priority.NORMAL);
    }

    private static long count(List<String> events, String type) {
        return events.stream().filter(type::equals).count();
    }
}
