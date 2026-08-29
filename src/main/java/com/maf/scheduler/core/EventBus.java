package com.maf.scheduler.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EventBus — 定时事件调度表 (Python 版 event_bus.py).
 *
 * 只承担"定时事件注册 / 取消 / 到期取出"的调度表职责; 事件过滤不在本类
 * (3 层过滤是每角色独立的 {@link AgentRole#evaluateEvent}).
 */
public class EventBus {

    private static final Logger logger = LoggerFactory.getLogger(EventBus.class);

    /** event_id → Event (trigger_tick 已写入). */
    protected final Map<String, Types.Event> tickSchedule = new LinkedHashMap<>();

    /** 向调度表注册一个定时事件. */
    public String registerEvent(Types.Event event, int tick) {
        event.triggerTick = tick;
        tickSchedule.put(event.id, event);
        logger.info("EventBus 注册定时事件: id={} type={} → tick {}", event.id, event.eventType, tick);
        return event.id;
    }

    /** 取消一个已注册的定时事件 (仅调度表中的). */
    public boolean cancelEvent(String eventId) {
        return tickSchedule.remove(eventId) != null;
    }

    /** 列出待触发的定时事件 (按触发 Tick 排序). */
    public List<Map<String, Object>> listScheduledEvents() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map.Entry<String, Types.Event>> entries = new ArrayList<>(tickSchedule.entrySet());
        entries.sort(Comparator.comparingInt(e -> e.getValue().triggerTick == null ? 0 : e.getValue().triggerTick));
        for (Map.Entry<String, Types.Event> e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("event_id", e.getKey());
            m.put("tick", e.getValue().triggerTick);
            m.put("type", e.getValue().eventType);
            m.put("target_role", e.getValue().targetRole);
            out.add(m);
        }
        return out;
    }

    /**
     * 检查并取回到期事件 (由时间线程周期性调用).
     *
     * @param currentTick 当前绝对 Tick.
     * @return 到期的事件列表 (已从调度表移除, 调用方负责投递).
     */
    protected List<Types.Event> checkDueEvents(int currentTick) {
        List<Types.Event> due = new ArrayList<>();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Types.Event> e : tickSchedule.entrySet()) {
            Types.Event ev = e.getValue();
            if (ev.triggerTick != null && currentTick >= ev.triggerTick) {
                toRemove.add(e.getKey());
                due.add(ev);
            }
        }
        for (String id : toRemove) {
            tickSchedule.remove(id);
        }
        return due;
    }
}
