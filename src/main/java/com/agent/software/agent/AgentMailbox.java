package com.agent.software.agent;

import com.agent.software.agent.task.Task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;

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

    /** 队列元素：任务 + 入队序号，保证同紧急度 FIFO。 */
    private record Entry(long seq, Task task) {
    }

    /** urgency 降序、同 urgency 按 seq 升序（FIFO）。 */
    private static final Comparator<Entry> ORDER =
            Comparator.comparingInt((Entry e) -> e.task().urgency().weight()).reversed()
                    .thenComparingLong(Entry::seq);

    private final PriorityQueue<Entry> ready = new PriorityQueue<>(ORDER);
    private final PriorityQueue<Entry> deferred = new PriorityQueue<>(ORDER);
    private long seqCounter;

    /** 入队；deferred=true 表示进入暂存队列。 */
    public synchronized void push(Task task, boolean deferred) {
        if (task == null) {
            return;
        }
        Entry entry = new Entry(++seqCounter, task);
        (deferred ? this.deferred : this.ready).add(entry);
    }

    /** 查看可执行队首（不弹出）。 */
    public synchronized Optional<Task> peek() {
        Entry head = ready.peek();
        return head == null ? Optional.empty() : Optional.of(head.task());
    }

    /** 弹出可执行队首。 */
    public synchronized Optional<Task> pop() {
        Entry head = ready.poll();
        return head == null ? Optional.empty() : Optional.of(head.task());
    }

    /** 上班 / 状态允许时，把暂存任务全部提升为可执行。 */
    public synchronized void promoteDeferred() {
        while (!deferred.isEmpty()) {
            ready.add(deferred.poll());
        }
    }

    public synchronized int readyDepth() {
        return ready.size();
    }

    public synchronized int deferredDepth() {
        return deferred.size();
    }

    /** 加锁快照（ready + deferred）。 */
    public synchronized List<Task> snapshot() {
        List<Entry> all = new ArrayList<>(ready);
        all.addAll(deferred);
        all.sort(ORDER);
        List<Task> out = new ArrayList<>(all.size());
        for (Entry e : all) {
            out.add(e.task());
        }
        return out;
    }

    /** 清空两个队列（读档前使用）。 */
    public synchronized void clear() {
        ready.clear();
        deferred.clear();
    }
}
