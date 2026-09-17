package com.agent.software.sim.event;

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
 */
public final class DefaultDeliveryPolicy implements DeliveryPolicy {

    public DefaultDeliveryPolicy(SaliencePolicy salience) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public DeliveryDecision decide(DeliveryContext context) {
        throw new UnsupportedOperationException("skeleton");
    }
}
