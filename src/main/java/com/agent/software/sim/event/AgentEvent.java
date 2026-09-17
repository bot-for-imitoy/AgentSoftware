package com.agent.software.sim.event;

import com.agent.software.kernel.Ids.EventId;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Tick;

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

    public AgentEvent {
        recipients = recipients == null ? Set.of() : Set.copyOf(recipients);
        payload = payload == null ? Payload.empty() : payload;
        fireAt = fireAt == null ? Optional.empty() : fireAt;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    /** 广播事件（所有角色参与投递决策）。 */
    public static AgentEvent broadcast(EventKind kind, Priority priority, Payload payload) {
        return new AgentEvent(EventId.generate(), kind, priority, Set.of(), payload,
                Optional.empty(), Instant.now());
    }

    /** 定向事件（只投递给一个角色）。 */
    public static AgentEvent toRole(RoleId target, EventKind kind, Priority priority, Payload payload) {
        return new AgentEvent(EventId.generate(), kind, priority, Set.of(target), payload,
                Optional.empty(), Instant.now());
    }

    /** 定时事件（到点后由调度表弹出）。 */
    public static AgentEvent scheduled(EventKind kind, Priority priority, Payload payload, Tick at) {
        return new AgentEvent(EventId.generate(), kind, priority, Set.of(), payload,
                Optional.of(at), Instant.now());
    }

    public boolean targeted() {
        return recipients.size() == 1;
    }

    public boolean broadcast() {
        return recipients.isEmpty();
    }

    /** 唯一收件人（仅当 {@link #targeted()} 为真时有意义）。 */
    public Optional<RoleId> target() {
        return targeted() ? Optional.of(recipients.iterator().next()) : Optional.empty();
    }

    /** 返回一个"改到指定时刻触发"的副本（编辑定时任务时使用）。 */
    public AgentEvent rescheduledTo(Tick at) {
        return new AgentEvent(id, kind, priority, recipients, payload, Optional.ofNullable(at), occurredAt);
    }

    @Override
    public String toString() {
        return "AgentEvent(" + id.value() + ", " + kind.wire() + ", " + priority
                + (targeted() ? ", target=" + recipients.iterator().next().value() : ", broadcast") + ")";
    }
}
