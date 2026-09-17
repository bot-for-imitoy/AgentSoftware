package com.agent.software.sim.event;

import com.agent.software.kernel.Ids.EventId;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Payload;
import com.agent.software.sim.clock.Tick;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * 不可变事件。
 *
 * <p>关键变化：收件人显式化（空集 = 广播），可选触发时刻；替代 master 中可变、字段公开的
 * {@code Types.Event}。是否投递由 {@code sim.event.DeliveryPolicy} 决定，事件本身不自带过滤逻辑。
 */
public record AgentEvent(
        EventId id,
        EventKind kind,
        Priority priority,
        Set<RoleId> recipients,
        Payload payload,
        Optional<Tick> fireAt,
        Instant occurredAt) {

    /** 广播事件（所有角色参与投递决策）。 */
    public static AgentEvent broadcast(EventKind kind, Priority priority, Payload payload) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 定向事件（只投递给一个角色）。 */
    public static AgentEvent toRole(RoleId target, EventKind kind, Priority priority, Payload payload) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 定时事件（到点后由调度表弹出）。 */
    public static AgentEvent scheduled(EventKind kind, Priority priority, Payload payload, Tick at) {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean targeted() {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean broadcast() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 返回一个"改到指定时刻触发"的副本（编辑定时任务时使用）。 */
    public AgentEvent rescheduledTo(Tick at) {
        throw new UnsupportedOperationException("skeleton");
    }
}
