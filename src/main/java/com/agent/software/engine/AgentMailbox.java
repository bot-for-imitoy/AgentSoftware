package com.agent.software.engine;

import com.agent.software.model.Task;

import java.util.List;
import java.util.Optional;

/**
 * 每角色优先队列（唯一写者 = 所属 {@link Agent}）。
 *
 * <p>按 urgency 降序、同 urgency FIFO。分两个队列，使投递策略的
 * {@code HOLD} 真正起作用（而不是像 master 那样"算完照样 submit"）：
 * <ul>
 *   <li>{@code ready} —— 立即可执行；</li>
 *   <li>{@code deferred} —— 暂存（下班 / 收尾 / 等待中收到普通事件），
 *       状态允许后由 {@link #promoteDeferred()} 提升。</li>
 * </ul>
 */
public final class AgentMailbox {

    /** 入队；deferred=true 表示进入暂存队列。 */
    public void push(Task task, boolean deferred) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 查看可执行队首（不弹出）。 */
    public Optional<Task> peek() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 弹出可执行队首。 */
    public Optional<Task> pop() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 上班 / 状态允许时，把暂存任务全部提升为可执行。 */
    public void promoteDeferred() {
        throw new UnsupportedOperationException("skeleton");
    }

    public int readyDepth() {
        throw new UnsupportedOperationException("skeleton");
    }

    public int deferredDepth() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 加锁快照（ready + deferred）。 */
    public List<Task> snapshot() {
        throw new UnsupportedOperationException("skeleton");
    }
}
