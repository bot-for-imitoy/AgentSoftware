package com.agent.software.agent;

import com.agent.software.kernel.Ids.RoleId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * talk wait=true 的同步等待协议（master {@code AgentRole} 里的 Condition 协议）。
 *
 * <p>从角色对象里独立出来：等待状态、回复信箱、被系统解阻塞，都是清楚的一小块。
 */
public final class WaitCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(WaitCoordinator.class);

    private final AgentStateMachine state;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition replyArrived = lock.newCondition();

    private RoleId waitingFor;
    private String replyBox;
    private AgentState stateBeforeWait;

    /** 不联动状态机的构造器（单测用）。 */
    public WaitCoordinator() {
        this(null);
    }

    /** 绑定角色的状态机：进入等待时迁移到 WAITING，结束时恢复。 */
    public WaitCoordinator(AgentStateMachine state) {
        this.state = state;
    }

    /** 进入等待，记录在等谁。 */
    public void begin(RoleId target) {
        lock.lock();
        try {
            waitingFor = target;
            replyBox = null;
            if (state != null) {
                stateBeforeWait = state.state();
                state.toWaiting();
            }
        } finally {
            lock.unlock();
        }
        logger.debug("进入等待：等待 {} 的回复", target == null ? "(未指定)" : target.value());
    }

    /**
     * 阻塞直到收到回复 / 被 abort / 超时。
     *
     * @param timeoutOrNull null 表示一直等
     */
    public Optional<String> await(Duration timeoutOrNull) {
        lock.lock();
        try {
            if (replyBox != null) {
                return Optional.of(replyBox);
            }
            try {
                if (timeoutOrNull == null) {
                    replyArrived.await();
                } else {
                    long nanos = Math.max(0L, timeoutOrNull.toNanos());
                    while (replyBox == null && nanos > 0L) {
                        nanos = replyArrived.awaitNanos(nanos);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.ofNullable(replyBox);
        } finally {
            lock.unlock();
        }
    }

    /** 同事回复到达。 */
    public void deliver(String reply) {
        lock.lock();
        try {
            replyBox = reply == null ? "" : reply;
            replyArrived.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 系统解阻塞（下班 / 收尾超时）：用一个合成回复唤醒等待者。 */
    public void abort(String syntheticReply) {
        lock.lock();
        try {
            if (replyBox == null) {
                replyBox = syntheticReply == null ? "" : syntheticReply;
                if (waitingFor != null) {
                    logger.info("等待被系统解阻塞（对方是 {}）", waitingFor.value());
                }
                replyArrived.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 结束等待。 */
    public void end() {
        lock.lock();
        try {
            waitingFor = null;
            replyBox = null;
            if (state != null && stateBeforeWait != null) {
                try {
                    state.to(stateBeforeWait);
                } catch (RuntimeException e) {
                    logger.debug("等待结束无法恢复状态 {}，改为 ON_DUTY_IDLE", stateBeforeWait);
                    state.toIdle();
                }
            }
            stateBeforeWait = null;
        } finally {
            lock.unlock();
        }
    }

    public boolean waiting() {
        lock.lock();
        try {
            return waitingFor != null;
        } finally {
            lock.unlock();
        }
    }

    public Optional<RoleId> waitingFor() {
        lock.lock();
        try {
            return Optional.ofNullable(waitingFor);
        } finally {
            lock.unlock();
        }
    }

    /** 测试/调试用：当前是否已经收到回复。 */
    public boolean hasReply() {
        lock.lock();
        try {
            return replyBox != null;
        } finally {
            lock.unlock();
        }
    }

    /** 仅在超时后用于清理（保持接口简单：等价于 tryLock 版 end()）。 */
    boolean tryEndWithin(long millis) {
        try {
            if (lock.tryLock(millis, TimeUnit.MILLISECONDS)) {
                try {
                    waitingFor = null;
                    replyBox = null;
                } finally {
                    lock.unlock();
                }
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }
}
