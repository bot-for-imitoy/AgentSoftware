package com.agent.software.agent.dispatch;

import com.agent.software.agent.Team;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TaskId;
import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.DeliveryPolicy;
import com.agent.software.sim.event.EventSink;
import com.agent.software.transcript.Transcript;

import java.util.Map;

/**
 * 事件唯一入口与投递器。
 *
 * <p>流程：解析收件人 → 逐个问 {@link DeliveryPolicy} → {@code DELIVER/HOLD} 经
 * {@link TaskFactory} 变成任务投给角色（HOLD 进暂存队列），{@code DROP} 只记日志。
 * master 把这段拆在 {@code EventDispatcher} 与被调用的 {@code AgentRole} 里。
 */
public final class EventRouter implements EventSink {

    private final Team team;
    private final DeliveryPolicy delivery;
    private final TaskFactory tasks;
    private final Transcript transcript;

    public EventRouter(Team team, DeliveryPolicy delivery, TaskFactory tasks, Transcript transcript) {
        this.team = team;
        this.delivery = delivery;
        this.tasks = tasks;
        this.transcript = transcript;
    }

    @Override
    public void publish(AgentEvent event) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 与 {@link #publish} 相同，但返回每个角色的取舍，供 demo/测试观察。 */
    public Map<RoleId, Routed> publishAndReport(AgentEvent event) {
        throw new UnsupportedOperationException("skeleton");
    }

    public record Routed(DeliveryPolicy.DeliveryVerdict verdict, String reason, TaskId taskId) {
    }
}
