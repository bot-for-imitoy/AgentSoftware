package com.agent.software.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * 每 endpoint 的限流排队，重试次数多者优先。
 *
 * <p><b>为什么需要它。</b>每个角色各持一个 {@link OpenAiClient} 且跑在各自的工作线程上，
 * 一旦 endpoint 开始限流（429/5xx），所有调用方会同时重试、把 endpoint 压得更死：
 * 已经重试最多次、最接近成功的请求反而排在新鲜请求后面。本类把这些尝试收进一个队列。
 *
 * <p><b>排队策略。</b>某次尝试上报 {@link #throttled(String)} 后 endpoint 进入"拥塞"状态；
 * 拥塞期间每次尝试都要先 {@link #acquire} 一个名额，名额严格按<b>重试次数从多到少</b>发放：
 * <ol>
 *   <li>重试次数少的调用方会被重试次数多的挡住，直到对方跑完本次尝试；</li>
 *   <li>重试次数相同者按到达顺序（FIFO）；</li>
 *   <li>同一时刻只放行一个尝试（当前请求跑完再放下一个）。</li>
 * </ol>
 *
 * <p><b>不是饿死。</b>等待者只是在"同时运行"的意义上让位；两次尝试之间的退避不持有名额，
 * 队列会从退避的空隙中排空。放弃排队（暂停/中断）的调用方会立刻从队列移除，不挡后面的人。
 *
 * <p><b>解除拥塞。</b>某次尝试成功且队列已空时，拥塞解除，本类重新完全透明（立即放行、无并发上限），
 * 直到下一次 429/5xx。
 *
 * <p><b>作用域。</b>同一 endpoint（通常按 Base URL 作 key）的所有客户端共享一个实例，
 * 这正是"跨角色、跨公司实例统一排序"的来源。
 *
 * <p>用 {@link ReentrantLock} + {@link Condition} 而非 {@code synchronized}/{@code wait()}：
 * 虚拟线程在监视器里 parking 会钉住载体线程，几十个角色一起排队时会饿死调度器。
 */
public final class RetryArbiter {

    private static final Logger logger = LoggerFactory.getLogger(RetryArbiter.class);

    /** 按 endpoint key 共享的仲裁器（不淘汰，规模等于用到的 endpoint 数）。 */
    private static final Map<String, RetryArbiter> SHARED = new ConcurrentHashMap<>();

    private final ReentrantLock lock = new ReentrantLock();
    /** 名额发放 / 拥塞解除时唤醒等待者；受 {@link #lock} 保护。 */
    private final Condition turnAvailable = lock.newCondition();

    /** 已排队、尚未获准的调用方，头部是下一个被放行者；受 {@link #lock} 保护。 */
    private final List<Waiter> waiting = new ArrayList<>();
    /** 当前持有名额、尚未释放的尝试数；受 {@link #lock} 保护。 */
    private int active;
    /** 从第一次 429/5xx 到"成功且队列为空"之间为 true；受 {@link #lock} 保护。 */
    private boolean congested;
    /** 到达序号：同一重试档次内的 FIFO 依据；受 {@link #lock} 保护。 */
    private long arrivals;
    /** endpoint 标识（仅日志用）。 */
    private String name = "<default>";

    /** 取得（或创建）某 endpoint 在 JVM 内共享的仲裁器。 */
    public static RetryArbiter forEndpoint(String endpointKey) {
        String key = endpointKey == null || endpointKey.isBlank() ? "<default>" : endpointKey.trim();
        return SHARED.computeIfAbsent(key, k -> {
            RetryArbiter arbiter = new RetryArbiter();
            arbiter.name = k;
            return arbiter;
        });
    }

    /** 清空全部共享仲裁器（测试隔离用）。 */
    public static void clearShared() {
        SHARED.clear();
    }

    /** 重试次数多者优先；同档次按到达顺序。 */
    private static final Comparator<Waiter> PRIORITY =
            Comparator.comparingInt((Waiter w) -> w.retries).reversed()
                    .thenComparingLong(w -> w.arrival);

    /** 一个已排队的调用方。 */
    private static final class Waiter {
        final int retries;   // 已重试次数：优先级键（越大越优先）
        final long arrival;  // 同档次的 FIFO 依据
        boolean granted;

        Waiter(int retries, long arrival) {
            this.retries = retries;
            this.arrival = arrival;
        }
    }

    /** 一次尝试占用的名额，用完必须释放。 */
    public final class Slot implements AutoCloseable {

        private boolean released;

        /** 归名额并声明本次尝试是否成功（成功且无人排队时解除拥塞）。 */
        public void release(boolean succeeded) {
            if (released) {
                return;
            }
            released = true;
            RetryArbiter.this.releaseSlot(succeeded);
        }

        /** 默认按未成功释放。 */
        @Override
        public void close() {
            release(false);
        }
    }

    /** 为一次尝试获取排队名额；keepWaiting 为假或线程中断时返回 null 表示放弃本次尝试。 */
    public Slot acquire(int retries, long pollMillis, BooleanSupplier keepWaiting) {
        long poll = Math.max(1L, pollMillis);
        lock.lock();
        try {
            // 快路径：endpoint 健康时立即放行，不排队也不设并发上限。
            if (!congested) {
                active++;
                return new Slot();
            }
            // 拥塞：入队并按优先级排序，等前面重试更深的人先跑完。
            Waiter me = new Waiter(retries, ++arrivals);
            waiting.add(me);
            waiting.sort(PRIORITY);
            maybeAdmit();
            while (!me.granted) {
                if (keepWaiting != null && !keepWaiting.getAsBoolean()) {
                    // 主动放弃：从队列移除，绝不挡住后面的人。
                    waiting.remove(me);
                    maybeAdmit();
                    return null;
                }
                try {
                    turnAvailable.await(poll, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    waiting.remove(me);
                    maybeAdmit();
                    return null;
                }
            }
            return new Slot();
        } finally {
            lock.unlock();
        }
    }

    /** 上报一次可重试的 429/5xx，使 endpoint 进入拥塞排队状态。 */
    public void throttled(String cause) {
        lock.lock();
        try {
            if (!congested) {
                congested = true;
                logger.info("endpoint {} 开始限流（{}）：按重试次数从多到少排队，重试最深者先完成", name, cause);
            }
        } finally {
            lock.unlock();
        }
    }

    /** 当前 endpoint 是否处于拥塞状态。 */
    public boolean isCongested() {
        lock.lock();
        try {
            return congested;
        } finally {
            lock.unlock();
        }
    }

    /** 释放名额；成功且队列已空时解除拥塞。 */
    private void releaseSlot(boolean succeeded) {
        lock.lock();
        try {
            if (active > 0) {
                active--;
            }
            if (succeeded && waiting.isEmpty() && active == 0) {
                congested = false;
                logger.debug("endpoint {} 恢复正常且无人在排队，拥塞解除", name);
            }
            maybeAdmit();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 给优先级最高的等待者发放名额；调用方须持有 {@link #lock}。
     *
     * <p>拥塞时一次只放行一个：让当前请求先跑完，再轮到下一个。
     */
    private void maybeAdmit() {
        if (waiting.isEmpty()) {
            return;
        }
        if (!congested) {
            // 防御分支：拥塞只在队列为空时解除，正常情况下不会走到这里。
            for (Waiter w : waiting) {
                w.granted = true;
                active++;
            }
            waiting.clear();
            turnAvailable.signalAll();
            return;
        }
        boolean admitted = false;
        while (!waiting.isEmpty() && active < 1) {
            Waiter next = waiting.remove(0);   // 重试次数最多、其次到达最早
            next.granted = true;
            active++;
            admitted = true;
        }
        if (admitted) {
            turnAvailable.signalAll();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return "RetryArbiter[" + name + " congested=" + congested
                    + " waiting=" + waiting.size() + " active=" + active + "]";
        } finally {
            lock.unlock();
        }
    }
}
