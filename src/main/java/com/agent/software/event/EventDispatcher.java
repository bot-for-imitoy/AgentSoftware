package com.agent.software.event;

import com.agent.software.core.Types;
import com.agent.software.role.AgentRole;
import com.agent.software.role.RolePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件分发器 (EventDispatcher) — 事件广播到所有角色的 Layer 1-3 过滤
 * (Python 版 dispatcher.py).
 *
 * trigger(event) 广播事件: 每个角色独立运行 Layer 1-3 过滤, PASS 事件
 * 自动转 Task 插入该角色的优先级队列.
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
     * 触发事件广播. 返回 {role_id: {accepted, reason, task_id}}.
     *
     * - 广播事件 (target_role=null): 所有角色各自运行 Layer 1-3 过滤.
     * - 定向事件 (target_role=xxx): 只投递给指定角色, 直接接受 (跳过过滤).
     */
    public Map<String, Map<String, Object>> trigger(Types.Event event) {
        stats.merge("total_events", 1, Integer::sum);
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();

        logger.info("EventDispatcher trigger: id={} type={}/{} priority={} target={}",
                event.id, event.source, event.eventType, event.priority,
                event.targetRole == null ? "(广播)" : event.targetRole);

        // 定向事件: 只投递给 target_role, 其他角色跳过
        if (event.targetRole != null) {
            if (pool.getRoleOrNull(event.targetRole) == null) {
                logger.warn("EventDispatcher: 定向事件目标角色 '{}' 不存在, 事件丢弃 (id={} type={})",
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
                m.put("reason", "定向事件, 目标: " + event.targetRole);
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
                // 已下班/收尾/等待中的角色不被非紧急定向事件打扰
                if (event.priority.value < Types.Priority.EMERGENCY.value
                        && (role.state == Types.AgentState.OFF_DUTY
                        || role.state == Types.AgentState.WRAPPING_UP
                        || role.state == Types.AgentState.WAIT)) {
                    stats.merge("roles_skipped", 1, Integer::sum);
                    logger.info("  → [{}] SKIPPED: 角色已{}, 非紧急定向事件不打扰",
                            roleName, role.state.value);
                    role.journal("定向通知 [" + event.source + "/" + event.eventType + "] ("
                            + event.priority + "): 跳过 — 角色已" + role.state.value);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("accepted", false);
                    m.put("reason", "角色已" + role.state.value + ", 非紧急定向事件不打扰");
                    m.put("task_id", null);
                    results.put(roleName, m);
                    continue;
                }
                accepted = true;
                reason = "定向任务提醒 (target_role=" + roleName + ")";
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
                role.journal("全局通知 [" + event.source + "/" + event.eventType + "] ("
                        + event.priority + "): 跳过 — " + reason);
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
