package com.agent.software.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link RetryArbiter} 的单元测试：endpoint 上报 429/5xx 之后，
 * 重试次数最多的请求先被服务，重试次数少的必须等它跑完，同档次按到达顺序（FIFO）。
 *
 * <p>迁移自 master 的 {@code RetryArbiterTest}，但新 API 不再暴露
 * {@code waitingCount()/activeCount()/highestWaitingRetries()} 这些内省方法，
 * 也不再支持 {@code maxConcurrentWhileCongested}（拥塞期固定一次只放行一个）。
 * 因此测试改用"槽位是否被准入 + 准入顺序 + 拥塞是否解除"来观察行为：
 * 一个等待者只要还在队列里，成功释放就无法解除拥塞（见
 * {@link RetryArbiter#release(boolean)} 的解除条件），这正好可以证明队列已空。
 */
class RetryArbiterTest {

    /** 轮询间隔取小值，避免依赖生产环境的 200ms 节奏。 */
    private static final long POLL = 5L;

    /**
     * 测试之间必须隔离共享仲裁器（本类以及其它测试类都用 {@code forEndpoint}）。
     */
    @BeforeEach
    void clearSharedArbitersBefore() {
        RetryArbiter.clearShared();
    }

    /** 再清一次，避免把状态泄漏给后续测试。 */
    @AfterEach
    void clearSharedArbitersAfter() {
        RetryArbiter.clearShared();
    }

    private static void awaitTrue(String what, BooleanSupplier condition) {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("等待超时: " + what);
    }

    /**
     * 自己的线程里停在仲裁器上的一个请求：被准入时记录 tag 并一直占着名额，
     * 直到测试放行，这样测试可以在队列仍然满着的时候观察顺序。
     */
    private static final class Caller {
        final String tag;
        /** 第一次调用 keepWaiting 说明该调用方已经真的入队并停下来了。 */
        final CountDownLatch parked = new CountDownLatch(1);
        final CountDownLatch admitted = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        volatile boolean gaveUp;
        volatile boolean succeeded = true;
        private final Thread thread;

        Caller(RetryArbiter arbiter, int retries, String tag, List<String> order) {
            this(arbiter, retries, tag, order, () -> true);
        }

        Caller(RetryArbiter arbiter, int retries, String tag, List<String> order,
               BooleanSupplier keepWaiting) {
            this.tag = tag;
            AtomicBoolean firstCheck = new AtomicBoolean(true);
            BooleanSupplier gate = () -> {
                // acquire 只在"已入队且尚未获准"时调用 keepWaiting，因此第一次回调即"已停稳"。
                if (firstCheck.compareAndSet(true, false)) {
                    parked.countDown();
                }
                return keepWaiting.getAsBoolean();
            };
            thread = new Thread(() -> {
                RetryArbiter.Slot slot = arbiter.acquire(retries, POLL, gate);
                if (slot == null) {
                    gaveUp = true;
                    admitted.countDown();
                    return;
                }
                order.add(tag);
                admitted.countDown();
                try {
                    letGo.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                slot.release(succeeded);
            }, "caller-" + tag);
            thread.setDaemon(true);
        }

        Caller start() {
            thread.start();
            return this;
        }

        boolean isAdmitted() {
            return admitted.getCount() == 0;
        }

        Caller go() {
            letGo.countDown();
            return this;
        }

        Caller join() {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return this;
        }
    }

    private static void awaitParked(Caller... callers) {
        for (Caller caller : callers) {
            awaitTrue("调用方 " + caller.tag + " 排队", () -> caller.parked.getCount() == 0);
        }
    }

    // ── 没有拥塞时，仲裁器必须完全透明 ──────────────────────

    @Test
    void testTransparentWhileTheEndpointIsHealthy() {
        RetryArbiter arbiter = RetryArbiter.forEndpoint("healthy");
        List<RetryArbiter.Slot> slots = new ArrayList<>();
        // 连续取 5 个名额而一个都不释放：健康时每次 acquire 都必须立刻返回，
        // 一旦被串行化（需要等前一个 release）这里会超时失败。
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 5; i++) {
                slots.add(arbiter.acquire(i, POLL, () -> true));
            }
        });
        assertEquals(5, slots.size());
        assertFalse(arbiter.isCongested(), "没有 429/5xx 时 endpoint 不应是拥塞状态");
        slots.forEach(slot -> slot.release(true));
        assertFalse(arbiter.isCongested());
    }

    // ── 目标策略：重试次数最多者先被服务 ────────────────────

    @Test
    void testMostRetriedRequestIsServedFirst() {
        RetryArbiter arbiter = RetryArbiter.forEndpoint("priority");
        arbiter.throttled("HTTP 429");
        List<String> order = new CopyOnWriteArrayList<>();

        // 一个尝试正在占用唯一名额
        RetryArbiter.Slot current = arbiter.acquire(0, POLL, () -> true);
        assertTrue(arbiter.isCongested());

        // 另外三个请求排队，故意按"错误"的顺序到达
        Caller fresh = new Caller(arbiter, 0, "retries0", order).start();
        Caller deep = new Caller(arbiter, 2, "retries2", order).start();
        Caller mid = new Caller(arbiter, 1, "retries1", order).start();
        awaitParked(fresh, deep, mid);

        assertFalse(fresh.isAdmitted());
        assertFalse(mid.isAdmitted());
        assertFalse(deep.isAdmitted());

        current.release(false);                       // 在途请求结束
        awaitTrue("重试最深的先被服务", deep::isAdmitted);
        assertEquals(List.of("retries2"), order, "重试次数少的绝不能越过重试次数多的");

        deep.go().join();
        awaitTrue("然后是中间档", mid::isAdmitted);
        assertEquals(List.of("retries2", "retries1"), order);

        mid.go().join();
        awaitTrue("最后是新鲜请求", fresh::isAdmitted);
        assertEquals(List.of("retries2", "retries1", "retries0"), order);

        fresh.go().join();
        assertFalse(arbiter.isCongested(), "成功且队列为空后 endpoint 重新开放");
    }

    @Test
    void testEqualRetryCountsAreServedInArrivalOrder() {
        RetryArbiter arbiter = RetryArbiter.forEndpoint("fifo");
        arbiter.throttled("HTTP 503");
        List<String> order = new CopyOnWriteArrayList<>();
        RetryArbiter.Slot current = arbiter.acquire(0, POLL, () -> true);

        // 逐个启动并等到"确实入队"再启动下一个，从而确定性地固定到达顺序。
        Caller first = new Caller(arbiter, 1, "first", order).start();
        awaitParked(first);
        Caller second = new Caller(arbiter, 1, "second", order).start();
        awaitParked(second);
        Caller third = new Caller(arbiter, 1, "third", order).start();
        awaitParked(third);

        current.release(false);
        awaitTrue("first 被服务", first::isAdmitted);
        first.go().join();
        awaitTrue("second 被服务", second::isAdmitted);
        second.go().join();
        awaitTrue("third 被服务", third::isAdmitted);
        third.go().join();

        assertEquals(List.of("first", "second", "third"), order);
    }

    @Test
    void testCongestionSurvivesASuccessWhileCallersAreStillParked() {
        RetryArbiter arbiter = RetryArbiter.forEndpoint("clear");
        arbiter.throttled("HTTP 429");
        List<String> order = new CopyOnWriteArrayList<>();

        Caller current = new Caller(arbiter, 0, "current", order).start();
        awaitTrue("current 拿到名额", current::isAdmitted);
        Caller parked = new Caller(arbiter, 0, "parked", order).start();
        awaitParked(parked);

        current.go().join();   // 成功了，但还有人排队等自己的回合
        assertTrue(arbiter.isCongested(),
                "只要还有请求在排队，一次成功就不能重新开放 endpoint");

        awaitTrue("排队者随后被服务", parked::isAdmitted);
        parked.go().join();
        assertFalse(arbiter.isCongested());
    }

    @Test
    void testCallerThatGivesUpNeverBlocksTheQueueBehindIt() {
        RetryArbiter arbiter = RetryArbiter.forEndpoint("abandon");
        arbiter.throttled("HTTP 429");
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicBoolean keepWaiting = new AtomicBoolean(true);

        RetryArbiter.Slot current = arbiter.acquire(0, POLL, () -> true);
        // 一个重试很深的调用方（最高优先级）排在新鲜调用方前面
        Caller deep = new Caller(arbiter, 9, "deep", order, keepWaiting::get).start();
        awaitParked(deep);
        Caller fresh = new Caller(arbiter, 0, "fresh", order).start();
        awaitParked(fresh);
        assertTrue(order.isEmpty(), "当前尝试还占着名额时谁都不能跑");

        keepWaiting.set(false);            // 深调用方放弃自己的回合（例如系统被暂停）
        awaitTrue("deep 已放弃", () -> deep.gaveUp);
        assertTrue(order.isEmpty(), "放弃的调用方不能偷偷发请求");

        current.release(false);
        awaitTrue("fresh 被服务", fresh::isAdmitted);
        assertEquals(List.of("fresh"), order);
        fresh.go().join();
    }

    @Test
    void testAbortConditionEndsTheWaitWithoutSending() {
        RetryArbiter arbiter = RetryArbiter.forEndpoint("abort");
        arbiter.throttled("HTTP 429");
        RetryArbiter.Slot current = arbiter.acquire(0, POLL, () -> true);

        RetryArbiter.Slot refused = arbiter.acquire(0, POLL, () -> false);
        assertNull(refused, "keepWaiting 一开始就是 false 时不能发放名额");
        assertTrue(arbiter.isCongested());

        // refused 必须已经从队列里移除：否则当前名额释放后拥塞无法解除。
        current.release(true);
        assertFalse(arbiter.isCongested(), "被拒绝的等待者不得留在队列里挡住拥塞解除");
    }

    /**
     * 完整队列必须总能排空：不泄漏名额、不互相死锁，服务顺序绝不能从深重试回到浅重试。
     */
    @Test
    void testEveryParkedCallerIsEventuallyServedInRetryOrder() {
        RetryArbiter arbiter = RetryArbiter.forEndpoint("stress");
        arbiter.throttled("HTTP 429");
        List<String> order = new CopyOnWriteArrayList<>();
        RetryArbiter.Slot holder = arbiter.acquire(0, POLL, () -> true);

        int n = 24;
        List<Caller> callers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            callers.add(new Caller(arbiter, i % 5, "c" + i, order).start());
        }
        awaitParked(callers.toArray(new Caller[0]));

        holder.release(false);
        Set<String> released = new HashSet<>();
        while (released.size() < n) {
            awaitTrue("又有一个调用方被服务", () -> order.size() > released.size());
            for (Caller caller : callers) {
                // 把名额交回给刚被准入的那个人
                if (caller.isAdmitted() && released.add(caller.tag)) {
                    caller.go();
                    break;
                }
            }
        }
        callers.forEach(Caller::join);

        assertEquals(n, order.size());
        assertFalse(arbiter.isCongested(), "队列排空后必须回到健康 endpoint");

        List<Integer> servedRetries = order.stream()
                .map(tag -> Integer.parseInt(tag.substring(1)) % 5)
                .toList();
        for (int i = 1; i < servedRetries.size(); i++) {
            assertTrue(servedRetries.get(i) <= servedRetries.get(i - 1),
                    "服务顺序绝不能回到更低的重试档次: " + servedRetries);
        }
    }

    /** {@link RetryArbiter.Slot#close()} 等价于按"未成功"释放：单独一次失败不解除拥塞。 */
    @Test
    void testCloseReleasesAsFailureAndKeepsCongestion() {
        RetryArbiter arbiter = RetryArbiter.forEndpoint("close");
        arbiter.throttled("HTTP 429");

        RetryArbiter.Slot slot = arbiter.acquire(0, POLL, () -> true);
        slot.close();
        assertTrue(arbiter.isCongested(), "未成功的释放不能解除拥塞");

        // 队列为空（active 也回到 0），下一次尝试仍能立刻拿到名额
        RetryArbiter.Slot next = arbiter.acquire(0, POLL, () -> true);
        next.release(true);
        assertFalse(arbiter.isCongested(), "成功且无人在排队时才解除拥塞");
    }

    // ── 按 endpoint 共享 ────────────────────────────────────

    @Test
    void testSharedPerEndpoint() {
        RetryArbiter.clearShared();
        try {
            assertSame(RetryArbiter.forEndpoint("http://a.example"),
                    RetryArbiter.forEndpoint("http://a.example"),
                    "同一 endpoint 的客户端必须共享同一套排序");
            assertNotSame(RetryArbiter.forEndpoint("http://a.example"),
                    RetryArbiter.forEndpoint("http://b.example"),
                    "不同 endpoint 之间不能互相阻塞");
            assertSame(RetryArbiter.forEndpoint(null), RetryArbiter.forEndpoint(""),
                    "null 与空串都落到默认 endpoint");
        } finally {
            RetryArbiter.clearShared();
        }
    }
}
