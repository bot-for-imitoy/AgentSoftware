package com.agent.software.event;

import com.agent.software.core.Types;
import com.agent.software.role.AgentRole;
import com.agent.software.role.RolePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Event dispatcher (EventDispatcher) — broadcasts events to all roles' Layer 1-3 filtering
 * (Python version dispatcher.py).
 *
 * trigger(event) broadcasts an event: each role independently runs Layer 1-3 filtering;
 * PASS events are automatically converted into Tasks inserted into that role's priority queue.
 */
public class EventDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(EventDispatcher.class);

    private final RolePool pool;
    private final Map<String, Integer> stats = new LinkedHashMap<>();

    public EventDispatcher(RolePool pool) {
        this.pool = pool;
        stats.put("total_events", 0);
        stats.put("total_tasks_created", 0);
        stats.put("roles_notified", 0);
        stats.put("roles_activated", 0);
        stats.put("roles_skipped", 0);
    }

    /**
     * Trigger an event broadcast. Returns {role_id: {accepted, reason, task_id}}.
     *
     * - Broadcast event (target_role=null): all roles each run Layer 1-3 filtering.
     * - Targeted event (target_role=xxx): delivered only to the specified role, accepted directly (skips filtering).
     */
    public Map<String, Map<String, Object>> trigger(Types.Event event) {
        stats.merge("total_events", 1, Integer::sum);
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();

        logger.info("EventDispatcher trigger: id={} type={}/{} priority={} target={}",
                event.id, event.source, event.eventType, event.priority,
                event.targetRole == null ? "(broadcast)" : event.targetRole);

        // Targeted event: only delivered to target_role; other roles skip
        if (event.targetRole != null) {
            if (pool.getRoleOrNull(event.targetRole) == null) {
                logger.warn("EventDispatcher: targeted event target role '{}' does not exist, event dropped (id={} type={})",
                        event.targetRole, event.id, event.eventType);
                return results;
            }
            for (AgentRole role : pool.allRoles()) {
                if (role.roleId.equals(event.targetRole)) {
                    continue;
                }
                stats.merge("roles_notified", 1, Integer::sum);
                stats.merge("roles_skipped", 1, Integer::sum);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("accepted", false);
                m.put("reason", "targeted event, target: " + event.targetRole);
                m.put("task_id", null);
                results.put(role.roleId, m);
            }
        }

        for (AgentRole role : pool.allRoles()) {
            String roleName = role.roleId;
            boolean accepted;
            String reason;
            if (event.targetRole != null) {
                if (!roleName.equals(event.targetRole)) {
                    continue;
                }
                // Roles that are off duty / wrapping up / waiting are not disturbed by non-urgent targeted events
                if (event.priority.value < Types.Priority.EMERGENCY.value
                        && (role.state == Types.AgentState.OFF_DUTY
                        || role.state == Types.AgentState.WRAPPING_UP
                        || role.state == Types.AgentState.WAIT)) {
                    stats.merge("roles_skipped", 1, Integer::sum);
                    logger.info("  → [{}] SKIPPED: role is already {}, non-urgent targeted events do not disturb",
                            roleName, role.state.value);
                    role.journal("Targeted notification [" + event.source + "/" + event.eventType + "] ("
                            + event.priority + "): skipped — role is already " + role.state.value);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("accepted", false);
                    m.put("reason", "role is already " + role.state.value + ", non-urgent targeted events do not disturb");
                    m.put("task_id", null);
                    results.put(roleName, m);
                    continue;
                }
                accepted = true;
                reason = "Targeted task reminder (target_role=" + roleName + ")";
            } else {
                stats.merge("roles_notified", 1, Integer::sum);
                Map.Entry<Boolean, String> r = role.evaluateEvent(event);
                accepted = r.getKey();
                reason = r.getValue();
            }

            String taskId = null;
            if (accepted) {
                AgentRole.Task task = role.eventToTask(event);
                role.addTask(task);
                taskId = task.taskId;
                stats.merge("roles_activated", 1, Integer::sum);
                stats.merge("total_tasks_created", 1, Integer::sum);
                logger.info("  → [{}] ACCEPTED: {} → Task {} (urgency={})",
                        roleName, reason, taskId, task.urgency);
            } else {
                stats.merge("roles_skipped", 1, Integer::sum);
                logger.info("  → [{}] SKIPPED: {}", roleName, reason);
                role.journal("Global notification [" + event.source + "/" + event.eventType + "] ("
                        + event.priority + "): skipped — " + reason);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accepted", accepted);
            m.put("reason", reason);
            m.put("task_id", taskId);
            results.put(roleName, m);
        }
        return results;
    }

    public Map<String, Integer> getStats() {
        return new LinkedHashMap<>(stats);
    }
}
