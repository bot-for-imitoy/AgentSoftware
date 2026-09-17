package com.agent.software.sim.clock;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.ScheduleId;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;
import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.EventKind;
import com.agent.software.sim.event.Priority;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.agent.software.kernel.Tick;
import com.agent.software.kernel.DayTick;

/**
 * 定时事件 / 任务提醒表。
 *
 * <p>对应 master {@code TimeEventBus} 的调度那半 + {@code NoteStore.scheduleReminder}。
 * 表本身不含线程：到期事件由 {@link ClockDriver} 弹出后交给 {@code EventSink}。
 *
 * <p>DayTick → 绝对 Tick 需要 {@link ShiftCalendar}，因此本表必须与时钟共用同一份日历
 * （构造时注入）；无参构造器只是给"默认 1 秒/tick、08:00–18:00"的场景兜底。
 */
public final class ScheduleTable implements ReminderScheduler {

    /** 决定 DayTick → 绝对 Tick 的日历；volatile 以配合 ClockDriver 构造期的对齐。 */
    private volatile ShiftCalendar calendar;
    /** 全部条目（含已触发），保留历史以便 cancel 能对已触发项返回 false。 */
    private final Map<ScheduleId, ScheduledEntry> entries = new LinkedHashMap<>();
    /** 已"生效"、允许 due() 弹出的条目。新班次开始由 activateDay 装入。 */
    private final Set<ScheduleId> active = new LinkedHashSet<>();
    /** 已生效到第几天；0 表示尚未上班，任何条目都不会生效。 */
    private int activatedDay = 0;

    /** 默认日历（1 模拟秒/tick，08:00–18:00）。与 {@code AppConfig.defaults().schedule()} 一致。 */
    public ScheduleTable() {
        this(ShiftCalendar.of(ShiftCalendar.DEFAULT_SECONDS_PER_TICK,
                ShiftCalendar.SHIFT_START_HOUR, ShiftCalendar.SHIFT_END_HOUR));
    }

    public ScheduleTable(ShiftCalendar calendar) {
        this.calendar = Objects.requireNonNull(calendar, "calendar");
    }

    /** 本表使用的日历（供 bootstrap / 测试核对与时钟是否一致）。 */
    public ShiftCalendar calendar() {
        return calendar;
    }

    /**
     * 与时钟对齐日历：由 {@link ClockDriver} 构造时在发现两者不一致时调用。
     *
     * <p>DayTick → 绝对 Tick 依赖 secondsPerTick，必须与时钟共享同一份日历，否则提醒会算错时刻。
     * 做成包内可见，避免把可变日历暴露成公共 API。
     */
    synchronized void rebindCalendar(ShiftCalendar calendar) {
        this.calendar = Objects.requireNonNull(calendar, "calendar");
    }

    @Override
    public synchronized ScheduleId schedule(String description, RoleId owner, DayTick at, Payload payload) {
        Objects.requireNonNull(at, "at");
        ScheduleId id = ScheduleId.generate();
        ScheduledEntry entry = new ScheduledEntry(id, Text.orEmpty(description), owner, at,
                payload != null ? payload : Payload.empty(), false);
        entries.put(id, entry);
        // 目标日已经生效（今天或更早）就立刻可触发；未来日要等那天的 activateDay。
        if (at.day() <= activatedDay) {
            active.add(id);
        }
        return id;
    }

    @Override
    public synchronized boolean cancel(ScheduleId id) {
        ScheduledEntry entry = id == null ? null : entries.get(id);
        if (entry == null || entry.fired()) {
            return false;
        }
        entries.remove(id);
        active.remove(id);
        return true;
    }

    /**
     * 改期（编辑提醒）。
     *
     * <p>已触发项也能改期：会把 {@code fired} 复位为 false 并按新日期重新决定是否生效，
     * 否则"刚响过又改到明天"将永远不再触发。id 不存在时返回 null。
     */
    public synchronized ScheduledEntry reschedule(ScheduleId id, DayTick at) {
        Objects.requireNonNull(at, "at");
        ScheduledEntry entry = id == null ? null : entries.get(id);
        if (entry == null) {
            return null;
        }
        ScheduledEntry updated = new ScheduledEntry(entry.id(), entry.description(), entry.owner(),
                at, entry.payload(), false);
        entries.put(id, updated);
        if (at.day() <= activatedDay) {
            active.add(id);
        } else {
            active.remove(id);
        }
        return updated;
    }

    /** 列出未触发项；owner 为 null 时列出全部。按触发时刻升序。 */
    public synchronized List<ScheduledEntry> list(RoleId owner) {
        List<ScheduledEntry> out = new ArrayList<>();
        for (ScheduledEntry entry : entries.values()) {
            if (entry.fired()) {
                continue;
            }
            if (owner != null && !owner.equals(entry.owner())) {
                continue;
            }
            out.add(entry);
        }
        out.sort(Comparator.comparingLong((ScheduledEntry entry) -> absoluteTick(entry).value()));
        return out;
    }

    /** 弹出所有到期项，转成 TASK_DUE 事件，并标记 fired（条目保留，便于 cancel 返回 false）。 */
    public synchronized List<AgentEvent> due(Tick now) {
        List<AgentEvent> out = new ArrayList<>();
        if (now == null) {
            return out;
        }
        for (ScheduledEntry entry : new ArrayList<>(entries.values())) {
            if (entry.fired() || !active.contains(entry.id())) {
                continue;
            }
            if (absoluteTick(entry).after(now)) {
                continue;
            }
            entries.put(entry.id(), new ScheduledEntry(entry.id(), entry.description(), entry.owner(),
                    entry.at(), entry.payload(), true));
            active.remove(entry.id());
            Payload payload = entry.payload()
                    .with("schedule_id", entry.id().value())
                    .with("description", entry.description());
            out.add(entry.owner() == null
                    ? AgentEvent.broadcast(EventKind.TASK_DUE, Priority.NORMAL, payload)
                    : AgentEvent.toRole(entry.owner(), EventKind.TASK_DUE, Priority.NORMAL, payload));
        }
        return out;
    }

    /**
     * 下一个触发点（供时钟快进）：未触发、已生效且绝对 tick {@code >= now} 的最小值。
     *
     * <p>这里用 {@code >=} 而不是严格大于：调用方（ClockDriver）会在真正快进前再过滤一次
     * "严格 after(now)"，而 due() 也在同一 tick 内处理"正好到点"的条目。
     */
    public synchronized Optional<Tick> nextFireTick(Tick now) {
        Tick best = null;
        for (ScheduledEntry entry : entries.values()) {
            if (entry.fired() || !active.contains(entry.id())) {
                continue;
            }
            Tick fire = absoluteTick(entry);
            if (now != null && fire.before(now)) {
                continue;
            }
            if (best == null || fire.before(best)) {
                best = fire;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 新班次开始：把当天应触发的任务装入日程（当日生效）。
     *
     * <p>语义：只把 {@code at.day() == day} 的未触发项加入 active，不会回溯更早的日期
     * （更早的日期若本来就该生效，早在上一次 activateDay 时已装入；若整段跳过了某天，
     * 那些提醒不会在新班次里突然补发）。条目本身始终保留，{@code activatedDay} 前移后
     * {@link #schedule} 的"当日/过去日立刻生效"判断随之改变。
     */
    public synchronized void activateDay(int day) {
        activatedDay = day;
        for (ScheduledEntry entry : entries.values()) {
            if (!entry.fired() && entry.at().day() == day) {
                active.add(entry.id());
            }
        }
    }

    /** DayTick → 绝对 tick，统一走与时钟共享的日历。 */
    private Tick absoluteTick(ScheduledEntry entry) {
        return calendar.at(entry.at().day(), entry.at().tickOfDay());
    }

    public record ScheduledEntry(ScheduleId id, String description, RoleId owner,
                                 DayTick at, Payload payload, boolean fired) {
    }
}
