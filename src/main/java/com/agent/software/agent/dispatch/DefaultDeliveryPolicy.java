package com.agent.software.agent.dispatch;

import com.agent.software.agent.dispatch.DeliveryPolicy.DeliveryContext;
import com.agent.software.agent.dispatch.DeliveryPolicy.DeliveryDecision;
import com.agent.software.agent.dispatch.SaliencePolicy.SalienceDecision;
import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.Priority;

/**
 * 默认投递策略。
 *
 * <p>规则（对齐 master 语义，但收拢到一处）：
 * <ul>
 *   <li>EMERGENCY：任何状态都 DELIVER；</li>
 *   <li>定向的定时任务提醒：即使 off-duty / waiting 也 DELIVER（只触发一次，丢了就永远丢了）；</li>
 *   <li>广播事件：先过 {@link SaliencePolicy}，不通过则 DROP；</li>
 *   <li>通过后若角色 off-duty / wrapping-up / waiting，则 HOLD，否则 DELIVER。</li>
 * </ul>
 *
 * <p>与 master 的一处刻意差异：master 对"定向但非紧急"的事件在角色下班时**直接丢弃**，
 * 而这里改成 HOLD —— 进暂存队列，第二天上班时提升。丢事件没有正当理由，暂存才有。
 */
public final class DefaultDeliveryPolicy implements DeliveryPolicy {

    /** 系统时间事件（source=time）不做内容显著性过滤，只受状态约束。 */
    private static final String TIME_SOURCE = "time";

    private final SaliencePolicy salience;

    public DefaultDeliveryPolicy(SaliencePolicy salience) {
        this.salience = salience == null
                ? (spec, event) -> new SalienceDecision(true, 1.0, 1.0, "未配置显著性策略，默认通过")
                : salience;
    }

    @Override
    public DeliveryDecision decide(DeliveryContext context) {
        if (context == null || context.event() == null) {
            return DeliveryDecision.drop("空事件");
        }
        AgentEvent event = context.event();

        // 1. 紧急事件永远穿透
        if (event.priority() == Priority.EMERGENCY) {
            return DeliveryDecision.deliver("紧急事件（" + event.priority() + "）穿透状态限制");
        }

        // 2. 定向的定时提醒：只触发一次，必须送达（下班/等待中也要投递给队列）
        if (context.scheduledReminder()) {
            return DeliveryDecision.deliver("定向定时提醒只触发一次，即使 "
                    + context.state() + " 也必须投递");
        }

        // 3. 广播事件（时间事件除外）先做内容相关性打分
        if (event.broadcast() && !TIME_SOURCE.equals(event.kind().source())) {
            SalienceDecision decision = salience.score(context.spec(), event);
            if (!decision.pass()) {
                return DeliveryDecision.drop("内容显著性不足：" + decision.reason());
            }
        }

        // 4. 状态约束：下班 / 收尾 / 等待中只暂存不执行
        if (context.state().holdsOrdinaryWork()) {
            return DeliveryDecision.hold("角色处于 " + context.state() + "，任务进入暂存队列");
        }
        return DeliveryDecision.deliver("状态允许（" + context.state() + "），立即执行");
    }
}
