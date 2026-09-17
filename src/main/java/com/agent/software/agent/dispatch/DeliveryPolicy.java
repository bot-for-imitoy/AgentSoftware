package com.agent.software.agent.dispatch;

import com.agent.software.agent.AgentState;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.sim.event.AgentEvent;

/**
 * 投递决策：一个事件现在能不能进入某个角色的队列。
 *
 * <p>这是全系统唯一的投递判断点。master 把同一件事拆成了三份：
 * {@code EventDispatcher} 的定向/定时特例 + {@code AgentRole.evaluateEvent} 的状态掩码
 * + {@code RolePool.roleLoop} 的下班保留。本接口只做决策，不碰队列与线程。
 */
public interface DeliveryPolicy {

    DeliveryDecision decide(DeliveryContext context);

    /** 决策所需事实。 */
    record DeliveryContext(AgentEvent event, RoleSpec spec, AgentState state,
                           boolean scheduledReminder) {
    }

    enum DeliveryVerdict {
        /** 立即转成任务并投递。 */
        DELIVER,
        /** 进入队列但 worker 暂不执行（下班 / 收尾 / 等待中）。 */
        HOLD,
        /** 直接丢弃（例如定向事件的目标不存在）。 */
        DROP
    }

    record DeliveryDecision(DeliveryVerdict verdict, String reason) {

        public static DeliveryDecision deliver(String why) {
            throw new UnsupportedOperationException("skeleton");
        }

        public static DeliveryDecision hold(String why) {
            throw new UnsupportedOperationException("skeleton");
        }

        public static DeliveryDecision drop(String why) {
            throw new UnsupportedOperationException("skeleton");
        }
    }
}
