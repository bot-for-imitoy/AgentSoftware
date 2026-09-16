package com.agent.software.engine;

import com.agent.software.kernel.Ids.RoleId;

import java.time.Duration;
import java.util.Optional;

/**
 * talk wait=true 的同步等待协议（master {@code AgentRole} 里的 Condition 协议）。
 *
 * <p>从角色对象里独立出来：等待状态、回复信箱、被系统解阻塞，都是清楚的一小块。
 */
public final class WaitCoordinator {

    /** 进入等待，记录在等谁。 */
    public void begin(RoleId target) {
        throw new UnsupportedOperationException("skeleton");
    }

    /**
     * 阻塞直到收到回复 / 被 abort / 超时。
     *
     * @param timeoutOrNull null 表示一直等
     */
    public Optional<String> await(Duration timeoutOrNull) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 同事回复到达。 */
    public void deliver(String reply) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 系统解阻塞（下班 / 收尾超时）：用一个合成回复唤醒等待者。 */
    public void abort(String syntheticReply) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 结束等待。 */
    public void end() {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean waiting() {
        throw new UnsupportedOperationException("skeleton");
    }

    public Optional<RoleId> waitingFor() {
        throw new UnsupportedOperationException("skeleton");
    }
}
