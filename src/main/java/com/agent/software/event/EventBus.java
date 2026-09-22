package com.agent.software.event;

import com.agent.software.AgentSystem;
import com.agent.software.role.Role;
import com.agent.software.role.RolePool;
import com.agent.software.utils.DataRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 事件总线：定时事件表 + 到点投递。
 *
 * <p>不做事件过滤（只要命中 target 就投递），队列排序由 {@link Role} 负责。
 * 唯一的闸门是下班：非工作时段普通事件进 {@link #hold(Event)}，次日
 * {@code SHIFT_START} 时 {@link #releaseHeld()}。这样 {@code IDLE} 才能代表
 * "全员空闲、可以跨天"。
 */
public class EventBus {

    private static final Logger logger = LoggerFactory.getLogger(EventBus.class);

    static {
        // 触发 Task 的静态注册，避免恢复时把 "task" 退化成 Event
        String taskType = Task.DATA_TYPE;
        if (taskType == null) {
            throw new IllegalStateException("unreachable");
        }
    }

    /** targetTime → 事件（同一 tick 多个事件保持插入顺序）。 */
    private final TreeMap<Long, List<Event>> schedule = new TreeMap<>();
    private final List<Event> held = new ArrayList<>();

    private AgentSystem system;
    private TimeBus timeBus;

    public EventBus() {
    }

    public void bind(AgentSystem system, TimeBus timeBus) {
        this.system = system;
        this.timeBus = timeBus;
    }

    // ── 排期 ────────────────────────────────────────────────────

    public String schedule(Event e) {
        return schedule(e, e.targetTime);
    }

    public String schedule(Event e, long tick) {
        if (e == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        e.targetTime = tick;
        synchronized (schedule) {
            schedule.computeIfAbsent(tick, k -> new ArrayList<>()).add(e);
        }
        return e.uuid;
    }

    public boolean cancel(String eventId) {
        synchronized (schedule) {
            for (Map.Entry<Long, List<Event>> entry : schedule.entrySet()) {
                if (entry.getValue().removeIf(e -> e.uuid.equals(eventId))) {
                    if (entry.getValue().isEmpty()) {
                        schedule.remove(entry.getKey());
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public List<Event> scheduled() {
        List<Event> out = new ArrayList<>();
        synchronized (schedule) {
            for (List<Event> list : schedule.values()) {
                out.addAll(list);
            }
        }
        return out;
    }

    public Event nextDue() {
        synchronized (schedule) {
            for (Map.Entry<Long, List<Event>> entry : schedule.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
        }
        return null;
    }

    // ── 投递 ────────────────────────────────────────────────────

    /** 由 {@link TimeBus} 回调：取出所有到期事件并投递。 */
    public void tick(long now) {
        List<Event> due = new ArrayList<>();
        synchronized (schedule) {
            while (!schedule.isEmpty() && schedule.firstKey() <= now) {
                Map.Entry<Long, List<Event>> first = schedule.pollFirstEntry();
                due.addAll(first.getValue());
            }
        }
        for (Event e : due) {
            post(e);
        }
    }

    /** 立即投递（下班时段普通事件转入暂存）。 */
    public void post(Event e) {
        if (e == null) {
            return;
        }
        boolean offHours = timeBus != null && !timeBus.isWorkingHours();
        if (offHours && !e.type.isControl()) {
            hold(e);
            return;
        }
        deliver(e);
    }

    /** 解析目标 → 入队；目标不在大组则丢弃。 */
    public void deliver(Event e) {
        List<Role> targets = resolveTargets(e);
        if (targets.isEmpty()) {
            logger.debug("EventBus dropped {} (target not in cohort)", e);
            return;
        }
        for (Role role : targets) {
            role.enqueue(e);
        }
    }

    /** 广播 = 全部大组成员；定向 = 该角色（必须在大组）。 */
    public List<Role> resolveTargets(Event e) {
        RolePool pool = system == null ? null : system.getRolePool();
        if (pool == null) {
            return List.of();
        }
        if (e.isBroadcast()) {
            return pool.all();
        }
        Role target = pool.find(e.targetRoleId);
        return target == null ? List.of() : List.of(target);
    }

    // ── 下班暂存 ────────────────────────────────────────────────

    public void hold(Event e) {
        synchronized (held) {
            held.add(e);
        }
    }

    public void releaseHeld() {
        List<Event> release;
        synchronized (held) {
            release = new ArrayList<>(held);
            held.clear();
        }
        for (Event e : release) {
            deliver(e);
        }
    }

    public List<Event> heldEvents() {
        synchronized (held) {
            return List.copyOf(held);
        }
    }

    // ── 持久化 ──────────────────────────────────────────────────

    public List<Map<String, String>> snapshot() {
        List<Map<String, String>> out = new ArrayList<>();
        for (Event e : scheduled()) {
            out.add(e.getData());
        }
        return out;
    }

    public void restore(List<Map<String, String>> records, RolePool pool) {
        if (records == null) {
            return;
        }
        for (Map<String, String> record : records) {
            String dataType = record.getOrDefault("data_type", Event.DATA_TYPE);
            if (!DataRegistry.supports(dataType)) {
                DataRegistry.register(dataType, () -> new Event(null, null, 0L, ""));
            }
            var data = DataRegistry.create(dataType);
            data.loadData(record);
            schedule((Event) data);
        }
        logger.info("EventBus restored {} scheduled event(s)", records.size());
    }
}
