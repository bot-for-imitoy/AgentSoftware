package com.agent.software.agent.dispatch;

import com.agent.software.agent.Agent;
import com.agent.software.agent.Team;
import com.agent.software.agent.dispatch.DeliveryPolicy.DeliveryContext;
import com.agent.software.agent.dispatch.DeliveryPolicy.DeliveryDecision;
import com.agent.software.agent.dispatch.DeliveryPolicy.DeliveryVerdict;
import com.agent.software.agent.task.Task;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TaskId;
import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.EventKind;
import com.agent.software.sim.event.EventSink;
import com.agent.software.transcript.Transcript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件唯一入口与投递器。
 *
 * <p>流程：解析收件人 → 逐个问 {@link DeliveryPolicy} → {@code DELIVER/HOLD} 经
 * {@link TaskFactory} 变成任务投给角色（HOLD 进暂存队列），{@code DROP} 只记日志。
 * master 把这段拆在 {@code EventDispatcher} 与被调用的 {@code AgentRole} 里。
 */
public final class EventRouter implements EventSink {

    private static final Logger logger = LoggerFactory.getLogger(EventRouter.class);

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
        publishAndReport(event);
    }

    /** 与 {@link #publish} 相同，但返回每个角色的取舍，供 demo/测试观察。 */
    public Map<RoleId, Routed> publishAndReport(AgentEvent event) {
        Map<RoleId, Routed> results = new LinkedHashMap<>();
        if (event == null) {
            return results;
        }
        logger.info("事件路由：id={} type={} priority={} {}",
                event.id().value(), event.kind().wire(), event.priority(),
                event.broadcast() ? "(广播)" : "(定向 " + event.target().map(RoleId::value).orElse("?") + ")");

        List<Agent> candidates = new ArrayList<>();
        if (event.targeted()) {
            RoleId target = event.target().orElse(null);
            var found = target == null ? java.util.Optional.<Agent>empty() : team.agent(target);
            if (found.isEmpty()) {
                logger.warn("定向事件的目标角色 {} 不存在，事件被丢弃（id={} type={}）",
                        target == null ? "?" : target.value(), event.id().value(), event.kind().wire());
                return results;
            }
            candidates.add(found.get());
        } else {
            candidates.addAll(team.agents());
        }

        for (Agent agent : candidates) {
            boolean reminder = EventKind.TASK_DUE.equals(event.kind());
            DeliveryDecision decision = delivery.decide(
                    new DeliveryContext(event, agent.spec(), agent.state(), reminder));

            TaskId taskId = null;
            if (decision.verdict() != DeliveryVerdict.DROP) {
                Task task = tasks.from(event, agent.id());
                agent.submit(task, decision.verdict() == DeliveryVerdict.HOLD);
                taskId = task.id();
            }
            results.put(agent.id(), new Routed(decision.verdict(), decision.reason(), taskId));

            switch (decision.verdict()) {
                case DELIVER -> logger.info("  → [{}] 投递：{}（任务 {}）",
                        agent.id().value(), decision.reason(), taskId == null ? "-" : taskId.value());
                case HOLD -> logger.info("  → [{}] 暂存：{}（任务 {}）",
                        agent.id().value(), decision.reason(), taskId == null ? "-" : taskId.value());
                case DROP -> logger.info("  → [{}] 丢弃：{}", agent.id().value(), decision.reason());
            }
        }

        // 系统轨迹：让 Web 端能看到事件路由结果（数量而不是每个角色的细节）
        if (transcript != null) {
            long delivered = results.values().stream().filter(r -> r.verdict() == DeliveryVerdict.DELIVER).count();
            long held = results.values().stream().filter(r -> r.verdict() == DeliveryVerdict.HOLD).count();
            long dropped = results.values().stream().filter(r -> r.verdict() == DeliveryVerdict.DROP).count();
            try {
                transcript.system("事件 " + event.kind().wire() + " (" + event.priority() + ")：投递 "
                        + delivered + " / 暂存 " + held + " / 丢弃 " + dropped);
            } catch (RuntimeException e) {
                logger.debug("写系统轨迹失败：{}", e.getMessage());
            }
        }
        return results;
    }

    public record Routed(DeliveryPolicy.DeliveryVerdict verdict, String reason, TaskId taskId) {
    }
}
