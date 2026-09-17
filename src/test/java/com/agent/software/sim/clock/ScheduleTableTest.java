package com.agent.software.sim.clock;

import com.agent.software.kernel.DayTick;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.ScheduleId;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Tick;
import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.EventKind;
import com.agent.software.sim.event.Priority;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScheduleTable} 定时任务表测试。
 *
 * <p>迁移自 master 的 {@code NoteReminderTest}（笔记提醒那部分）与 {@code TimeEventBus} 的
 * 调度语义：按天激活、到期转 {@code TASK_DUE}、只触发一次、列出/取消/改期、下一个触发点。
 * master 把这张表和时钟线程、日历换算全塞在一个类里，这里只测表的职责。
 *
 * <p>与 master 的两处有意差异（新 API 决定，见 {@code redesign/PLAN.md} 的 ScheduleTable 契约）：
 * <ul>
 *   <li>提醒不再限制"必须在班次内"（master 的 {@code taskTickMax} 越界抛
 *       {@code IllegalArgumentException}）——表已退化为通用内存表；</li>
 *   <li>{@code reschedule(id, at)} 没有 {@code now} 参数，因此不再有"改到过去要报错"的守卫；
 *       已触发项允许改期重新武装。</li>
 * </ul>
 */
class ScheduleTableTest {

    private static final ShiftCalendar CAL = ShiftCalendar.of(1.0, 8, 18);
    private static final RoleId CEO = new RoleId("ceo");

    private final ScheduleTable schedule = new ScheduleTable(CAL);

    // ── 按天激活 ────────────────────────────────────────────────

    @Test
    void 未来日期的提醒要等当天激活() {
        ScheduleId id = schedule.schedule("明天的事", CEO, new DayTick(2, 5), Payload.of("title", "x"));
        assertNotNull(id);

        assertTrue(schedule.nextFireTick(new Tick(0)).isEmpty(), "未 activateDay(2) 前不生效");
        assertTrue(schedule.due(new Tick(86400 + 5)).isEmpty(), "未激活的条目不得触发");

        schedule.activateDay(2);
        assertEquals(86405L, schedule.nextFireTick(new Tick(0)).orElseThrow().value());
        assertEquals(1, schedule.due(new Tick(86405)).size());
    }

    @Test
    void 已上班当天创建的提醒立即生效() {
        schedule.activateDay(1);
        schedule.schedule("今天的事", CEO, new DayTick(1, 5), Payload.empty());

        assertEquals(1, schedule.list(null).size());
        assertEquals(1, schedule.due(new Tick(5)).size());
    }

    @Test
    void 跨天激活不回溯补发更早的提醒() {
        schedule.schedule("第一天", CEO, new DayTick(1, 5), Payload.empty());
        schedule.activateDay(2); // 整段跳过了第 1 天

        assertTrue(schedule.due(new Tick(86400 + 10)).isEmpty(), "跳过的天不应在新班次补发");
        assertTrue(schedule.nextFireTick(new Tick(86400)).isEmpty());
    }

    // ── 到期 → TASK_DUE ────────────────────────────────────────

    @Test
    void 到期转为定向TASK_DUE并只触发一次() {
        ScheduleId id = schedule.schedule("下午开会", CEO, new DayTick(1, 60),
                Payload.of("title", "开会"));
        schedule.activateDay(1);

        assertTrue(schedule.due(new Tick(59)).isEmpty());

        List<AgentEvent> due = schedule.due(new Tick(60));
        assertEquals(1, due.size());
        AgentEvent event = due.get(0);
        assertEquals(EventKind.TASK_DUE, event.kind());
        assertEquals(Priority.NORMAL, event.priority());
        assertEquals(CEO, event.target().orElseThrow());
        assertEquals(id.value(), event.payload().stringOr("schedule_id", ""));
        assertEquals("下午开会", event.payload().stringOr("description", ""));

        assertTrue(schedule.due(new Tick(60)).isEmpty(), "同一条提醒不得重复触发");
        assertTrue(schedule.list(null).isEmpty(), "触发后不再出现在未触发列表");
    }

    @Test
    void 无owner的条目广播TASK_DUE() {
        schedule.schedule("全员提醒", null, new DayTick(1, 10), Payload.empty());
        schedule.activateDay(1);

        List<AgentEvent> due = schedule.due(new Tick(10));
        assertEquals(1, due.size());
        assertTrue(due.get(0).broadcast());
    }

    // ── 取消 ────────────────────────────────────────────────────

    @Test
    void 取消未触发条目成功且幂等() {
        ScheduleId id = schedule.schedule("取消我", CEO, new DayTick(1, 10), Payload.empty());
        schedule.activateDay(1);

        assertTrue(schedule.cancel(id));
        assertFalse(schedule.cancel(id));
        assertFalse(schedule.cancel(null));
        assertTrue(schedule.list(null).isEmpty());
        assertTrue(schedule.due(new Tick(10)).isEmpty());
    }

    @Test
    void 已触发的条目不能再取消() {
        ScheduleId id = schedule.schedule("响过了", CEO, new DayTick(1, 10), Payload.empty());
        schedule.activateDay(1);
        assertEquals(1, schedule.due(new Tick(10)).size());

        assertFalse(schedule.cancel(id), "条目保留以便 cancel 对已触发项返回 false");
    }

    // ── 改期 ────────────────────────────────────────────────────

    @Test
    void 改期到未来的天会先失效再按新日期激活() {
        ScheduleId id = schedule.schedule("改期", CEO, new DayTick(1, 10), Payload.empty());
        schedule.activateDay(1);
        assertEquals(10L, schedule.nextFireTick(new Tick(0)).orElseThrow().value());

        ScheduleTable.ScheduledEntry updated = schedule.reschedule(id, new DayTick(2, 20));
        assertNotNull(updated);
        assertEquals(new DayTick(2, 20), updated.at());
        assertFalse(updated.fired());
        assertTrue(schedule.nextFireTick(new Tick(0)).isEmpty(), "改到未来日后当前不再生效");

        schedule.activateDay(2);
        assertEquals(86420L, schedule.nextFireTick(new Tick(0)).orElseThrow().value());
    }

    @Test
    void 已触发条目可以改期重新武装() {
        ScheduleId id = schedule.schedule("再响一次", CEO, new DayTick(1, 10), Payload.empty());
        schedule.activateDay(1);
        assertEquals(1, schedule.due(new Tick(10)).size());

        ScheduleTable.ScheduledEntry updated = schedule.reschedule(id, new DayTick(1, 200));
        assertFalse(updated.fired(), "改期应把 fired 复位，否则刚响过的提醒永远不再触发");
        assertEquals(1, schedule.due(new Tick(200)).size());
    }

    @Test
    void 改期未知id返回null() {
        assertNull(schedule.reschedule(ScheduleId.generate(), new DayTick(1, 1)));
    }

    // ── 下一个触发点 ────────────────────────────────────────────

    @Test
    void 下一个触发点取最早生效未触发项() {
        ScheduleId late = schedule.schedule("晚", CEO, new DayTick(1, 300), Payload.empty());
        schedule.schedule("早", CEO, new DayTick(1, 100), Payload.empty());
        schedule.activateDay(1);

        assertEquals(100L, schedule.nextFireTick(new Tick(0)).orElseThrow().value());
        assertTrue(schedule.cancel(late));
        assertEquals(100L, schedule.nextFireTick(new Tick(0)).orElseThrow().value());
        assertEquals(100L, schedule.nextFireTick(new Tick(100)).orElseThrow().value(),
                "ScheduleTable 用 >= now，严格未来由 ClockDriver 收紧");
        assertTrue(schedule.nextFireTick(new Tick(101)).isEmpty());
    }

    // ── 列表 ────────────────────────────────────────────────────

    @Test
    void 列表按触发时刻升序并可按拥有者过滤() {
        RoleId cto = new RoleId("cto");
        schedule.schedule("ceo-晚", CEO, new DayTick(1, 300), Payload.empty());
        schedule.schedule("ceo-早", CEO, new DayTick(1, 100), Payload.empty());
        schedule.schedule("cto-中", cto, new DayTick(1, 200), Payload.empty());
        schedule.activateDay(1);

        List<ScheduleTable.ScheduledEntry> all = schedule.list(null);
        assertEquals(List.of("ceo-早", "cto-中", "ceo-晚"),
                all.stream().map(ScheduleTable.ScheduledEntry::description).toList());

        List<ScheduleTable.ScheduledEntry> ceoOnly = schedule.list(CEO);
        assertEquals(2, ceoOnly.size());
        assertTrue(ceoOnly.stream().allMatch(e -> CEO.equals(e.owner())));
    }
}
