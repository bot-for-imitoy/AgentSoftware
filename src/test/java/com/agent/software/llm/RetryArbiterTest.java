package com.agent.software.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@link RetryArbiter} — the ordering rule applied once an endpoint reports 429/5xx:
 * the request with the highest retry count is served first, requests that have retried fewer times
 * are blocked until it has finished, and the rest follow in arrival order.
 */
class RetryArbiterTest {

    /** Short poll so the tests do not depend on the production 200ms cadence. */
    private static final long POLL = 5L;

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
        fail("timed out waiting for: " + what);
    }

    /**
     * One request parked on the arbiter from its own thread. It records its tag when it is admitted
     * and holds the slot until the test lets it go, so the test can observe the queue while it is
     * still full.
     */
    private static final class Caller {
        final String tag;
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
            thread = new Thread(() -> {
                RetryArbiter.Slot slot = arbiter.acquire(retries, POLL, keepWaiting);
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

    // ── Outside congestion the arbiter must be invisible ──────

    @Test
    void testTransparentWhileTheEndpointIsHealthy() {
        RetryArbiter arbiter = new RetryArbiter("healthy");
        List<RetryArbiter.Slot> slots = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            slots.add(arbiter.acquire(i, POLL, () -> true));
        }
        // nobody queues and nothing is serialized as long as no 429/5xx was seen
        assertEquals(0, arbiter.waitingCount());
        assertEquals(5, arbiter.activeCount());
        assertFalse(arbiter.isCongested());
        slots.forEach(s -> s.release(true));
        assertEquals(0, arbiter.activeCount());
    }

    // ── The requested policy: highest retry count is served first ──

    @Test
    void testMostRetriedRequestIsServedFirst() {
        RetryArbiter arbiter = new RetryArbiter("priority");
        arbiter.throttled("HTTP 429");
        List<String> order = new CopyOnWriteArrayList<>();

        // one attempt is in flight and holds the single slot
        RetryArbiter.Slot current = arbiter.acquire(0, POLL, () -> true);
        assertTrue(arbiter.isCongested());

        // three further requests park, deliberately arriving in the "wrong" order
        Caller fresh = new Caller(arbiter, 0, "retries0", order).start();
        Caller deep = new Caller(arbiter, 2, "retries2", order).start();
        Caller mid = new Caller(arbiter, 1, "retries1", order).start();
        awaitTrue("all three parked", () -> arbiter.waitingCount() == 3);

        // the deepest caller is the one the arbiter will serve next
        assertEquals(2, arbiter.highestWaitingRetries());
        assertFalse(fresh.isAdmitted());
        assertFalse(mid.isAdmitted());
        assertFalse(deep.isAdmitted());

        current.release(false);                       // the in-flight request is done
        awaitTrue("the deepest caller is served", deep::isAdmitted);
        assertEquals(List.of("retries2"), order, "a lower retry count must not overtake a higher one");

        deep.go().join();
        awaitTrue("then the middle caller", mid::isAdmitted);
        assertEquals(List.of("retries2", "retries1"), order);

        mid.go().join();
        awaitTrue("then the fresh caller", fresh::isAdmitted);
        assertEquals(List.of("retries2", "retries1", "retries0"), order);

        fresh.go().join();
        assertTrue(arbiter.waitingCount() == 0 && arbiter.activeCount() == 0);
        assertFalse(arbiter.isCongested(), "a success with an empty queue reopens the endpoint");
    }

    @Test
    void testEqualRetryCountsAreServedInArrivalOrder() {
        RetryArbiter arbiter = new RetryArbiter("fifo");
        arbiter.throttled("HTTP 503");
        List<String> order = new CopyOnWriteArrayList<>();
        RetryArbiter.Slot current = arbiter.acquire(0, POLL, () -> true);

        Caller first = new Caller(arbiter, 1, "first", order).start();
        awaitTrue("first parked", () -> arbiter.waitingCount() == 1);
        Caller second = new Caller(arbiter, 1, "second", order).start();
        awaitTrue("second parked", () -> arbiter.waitingCount() == 2);
        Caller third = new Caller(arbiter, 1, "third", order).start();
        awaitTrue("third parked", () -> arbiter.waitingCount() == 3);

        current.release(false);
        awaitTrue("first served", first::isAdmitted);
        first.go().join();
        awaitTrue("second served", second::isAdmitted);
        second.go().join();
        awaitTrue("third served", third::isAdmitted);
        third.go().join();

        assertEquals(List.of("first", "second", "third"), order);
    }

    @Test
    void testCongestionSurvivesASuccessWhileCallersAreStillParked() {
        RetryArbiter arbiter = new RetryArbiter("clear");
        arbiter.throttled("HTTP 429");
        List<String> order = new CopyOnWriteArrayList<>();

        Caller current = new Caller(arbiter, 0, "current", order).start();
        awaitTrue("current holds the slot", current::isAdmitted);
        Caller parked = new Caller(arbiter, 0, "parked", order).start();
        awaitTrue("parked behind it", () -> arbiter.waitingCount() == 1);

        current.go().join();   // succeeds, but a caller is still waiting its turn
        assertTrue(arbiter.isCongested(),
                "one success must not reopen the endpoint while requests are still parked");

        awaitTrue("the parked caller is served next", parked::isAdmitted);
        parked.go().join();
        assertFalse(arbiter.isCongested());
    }

    @Test
    void testCallerThatGivesUpNeverBlocksTheQueueBehindIt() {
        RetryArbiter arbiter = new RetryArbiter("abandon");
        arbiter.throttled("HTTP 429");
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicBoolean keepWaiting = new AtomicBoolean(true);

        RetryArbiter.Slot current = arbiter.acquire(0, POLL, () -> true);
        // a deep caller (highest priority) parks ahead of a fresh one
        Caller deep = new Caller(arbiter, 9, "deep", order, keepWaiting::get).start();
        awaitTrue("deep caller parked", () -> arbiter.waitingCount() == 1);
        Caller fresh = new Caller(arbiter, 0, "fresh", order).start();
        awaitTrue("fresh caller parked too", () -> arbiter.waitingCount() == 2);
        assertTrue(order.isEmpty(), "nobody may run while the current attempt holds the slot");

        keepWaiting.set(false);            // the deep caller abandons its turn (e.g. system paused)
        awaitTrue("deep caller gave up", () -> deep.gaveUp);
        assertEquals(1, arbiter.waitingCount());

        current.release(false);
        awaitTrue("the fresh caller is served", fresh::isAdmitted);
        assertEquals(List.of("fresh"), order);
        fresh.go().join();
    }

    @Test
    void testAbortConditionEndsTheWaitWithoutSending() {
        RetryArbiter arbiter = new RetryArbiter("abort");
        arbiter.throttled("HTTP 429");
        RetryArbiter.Slot current = arbiter.acquire(0, POLL, () -> true);

        RetryArbiter.Slot refused = arbiter.acquire(0, POLL, () -> false);
        assertNull(refused, "an already-false abort condition must not be granted a slot");
        assertEquals(0, arbiter.waitingCount());
        assertEquals(1, arbiter.activeCount());

        current.release(true);
    }

    @Test
    void testConfiguredConcurrencyWhileCongested() {
        RetryArbiter arbiter = new RetryArbiter("cap");
        arbiter.maxConcurrentWhileCongested = 2;
        arbiter.throttled("HTTP 429");
        List<String> order = new CopyOnWriteArrayList<>();

        Caller a = new Caller(arbiter, 1, "a", order).start();
        Caller b = new Caller(arbiter, 1, "b", order).start();
        Caller c = new Caller(arbiter, 1, "c", order).start();
        awaitTrue("two run together, the third waits",
                () -> arbiter.activeCount() == 2 && arbiter.waitingCount() == 1);

        a.go().join();
        b.go().join();
        awaitTrue("the third follows", c::isAdmitted);
        c.go().join();
        assertEquals(3, order.size());
    }

    /**
     * A full queue must always drain: no slot may leak, nothing may deadlock, and the served order
     * must never move from a deeper retry count back to a shallower one.
     */
    @Test
    void testEveryParkedCallerIsEventuallyServedInRetryOrder() {
        RetryArbiter arbiter = new RetryArbiter("stress");
        arbiter.throttled("HTTP 429");
        List<String> order = new CopyOnWriteArrayList<>();
        RetryArbiter.Slot holder = arbiter.acquire(0, POLL, () -> true);

        int n = 24;
        List<Caller> callers = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            callers.add(new Caller(arbiter, i % 5, "c" + i, order).start());
        }
        awaitTrue("all " + n + " callers parked", () -> arbiter.waitingCount() == n);
        assertEquals(4, arbiter.highestWaitingRetries());

        holder.release(false);
        java.util.Set<String> released = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) {
            awaitTrue("a further caller is served", () -> order.size() > released.size());
            for (Caller c : callers) {
                if (c.isAdmitted() && released.add(c.tag)) {   // hand the slot on to whoever got it
                    c.go();
                    break;
                }
            }
        }
        callers.forEach(Caller::join);

        assertEquals(n, order.size());
        assertEquals(0, arbiter.waitingCount());
        assertEquals(0, arbiter.activeCount());
        assertFalse(arbiter.isCongested(), "the queue must drain back to a healthy endpoint");

        List<Integer> servedRetries = order.stream()
                .map(tag -> Integer.parseInt(tag.substring(1)) % 5)
                .toList();
        for (int i = 1; i < servedRetries.size(); i++) {
            assertTrue(servedRetries.get(i) <= servedRetries.get(i - 1),
                    "the served order must never go back to a lower retry count: " + servedRetries);
        }
    }

    // ── Shared per endpoint ────────────────────────────────
    @Test
    void testSharedPerEndpoint() {
        RetryArbiter.clearShared();
        try {
            assertTrue(RetryArbiter.forEndpoint("http://a.example") == RetryArbiter.forEndpoint("http://a.example"),
                    "clients of one endpoint must share the ordering");
            assertFalse(RetryArbiter.forEndpoint("http://a.example") == RetryArbiter.forEndpoint("http://b.example"),
                    "separate endpoints must not block each other");
            assertTrue(RetryArbiter.forEndpoint(null) == RetryArbiter.forEndpoint(""));
        } finally {
            RetryArbiter.clearShared();
        }
    }
}
