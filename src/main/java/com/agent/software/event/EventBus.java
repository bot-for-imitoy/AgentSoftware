package com.agent.software.event;

import com.agent.software.core.Types;
import com.agent.software.role.AgentRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EventBus — the timed event schedule table (Python version event_bus.py).
 *
 * It only serves as the schedule table for "timed event registration / cancellation / due retrieval";
 * event filtering is not in this class (3-layer filtering is per-role independent
 * {@link AgentRole#evaluateEvent}).
 */
public class EventBus {

    private static final Logger logger = LoggerFactory.getLogger(EventBus.class);

    /** event_id → Event (trigger_tick already written). */
    protected final Map<String, Types.Event> tickSchedule = new LinkedHashMap<>();

    /** Register a timed event with the schedule table. */
    public String registerEvent(Types.Event event, int tick) {
        event.triggerTick = tick;
        tickSchedule.put(event.id, event);
        logger.info("EventBus registered timed event: id={} type={} → tick {}", event.id, event.eventType, tick);
        return event.id;
    }

    /** Cancel a registered timed event (only those in the schedule table). */
    public boolean cancelEvent(String eventId) {
        return tickSchedule.remove(eventId) != null;
    }

    /** List scheduled events pending trigger (sorted by trigger tick). */
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
     * Check and retrieve due events (called periodically by the time thread).
     *
     * @param currentTick the current absolute tick.
     * @return the list of due events (removed from the schedule table; the caller is responsible for dispatch).
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
