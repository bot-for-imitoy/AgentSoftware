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
 * Priority gate that orders the concurrent chat/completions attempts of one endpoint while that
 * endpoint is throttling us.
 *
 * <p><b>Why it exists.</b> Every role owns its own {@link OpenAICompatLLM} and runs on its own
 * worker thread, so a 429 is never seen by one caller alone: the moment the endpoint starts
 * rate-limiting, every caller is retrying against it in lockstep. That keeps the endpoint
 * saturated and mostly produces more 429s — the request that was already furthest along waits
 * behind a fresh one, and the whole team makes no progress. This arbiter funnels those attempts
 * instead of letting them race.
 *
 * <p><b>Ordering policy.</b> As soon as one attempt observes a retryable API error — HTTP 429
 * or 5xx, reported through {@link #throttled(String)} — the endpoint becomes <em>congested</em>.
 * While congested, a caller that is about to send an attempt must first take a {@link Slot} from
 * the arbiter, and slots are handed out strictly by <b>retry count, highest first</b>:
 * <ol>
 *   <li>a caller whose retry count is <em>lower</em> than the retry count of another parked
 *       caller is <b>blocked</b> until that other caller has finished its attempt — so the
 *       request that has already been retried more often is the one that gets to complete
 *       first;</li>
 *   <li>callers with the <em>same</em> retry count are served in arrival order (FIFO), so the
 *       remaining requests are handled one after another;</li>
 *   <li>at most {@link #maxConcurrentWhileCongested} attempt(s) run at a time — by default one,
 *       i.e. the current request finishes before the next one starts.</li>
 * </ol>
 * A request that has not retried at all yet therefore always yields to a request that is deeper
 * into its retry budget. This is deliberate: the deepest caller has already spent the most time
 * and tokens, and is the one that can still complete instead of being wasted.
 *
 * <p><b>Not a lockout.</b> A parked caller is only excluded from <em>running at the same time</em>
 * as a deeper caller; it is not denied service. Between two attempts a caller waits out its retry
 * delay without holding a slot, so the queue drains into those gaps and every request still gets
 * its turn. A caller that abandons its turn (system paused, thread interrupted) is removed from
 * the queue immediately and never holds back the callers behind it.
 *
 * <p><b>Leaving congestion.</b> The endpoint stops being congested once an attempt succeeds and
 * nobody is parked behind it; the arbiter is then fully transparent again — attempts are granted
 * immediately, with no queueing and no concurrency limit — until the next 429/5xx. Callers that
 * are still in backoff do not keep the endpoint congested by themselves.
 *
 * <p><b>Scope.</b> One arbiter is shared by every LLM client that talks to the same endpoint
 * ({@link #forEndpoint(String)}, keyed by Base URL), which is exactly what makes the ordering
 * global across roles and across {@link com.agent.software.AgentSystem} instances in one JVM.
 * {@link #acquire} never blocks indefinitely: the caller passes its own abort condition, which is
 * re-checked every {@link #POLL_MILLIS} while it waits its turn.
 *
 * <p><b>Virtual threads.</b> The queue uses a {@link ReentrantLock} and a {@link Condition} rather
 * than {@code synchronized}/{@code wait()}: parking a caller inside a monitor would pin its carrier
 * thread, and with dozens of roles all parked at once that would starve the scheduler that runs
 * them.
 */
public final class RetryArbiter {

    private static final Logger logger = LoggerFactory.getLogger(RetryArbiter.class);

    /** Arbiters shared per endpoint key, normally the Base URL of the provider. */
    private static final Map<String, RetryArbiter> SHARED = new ConcurrentHashMap<>();

    /**
     * How often {@link #acquire} re-checks the caller's abort condition while it waits its turn
     * (a paused system must not leave requests parked in the queue).
     */
    public static final long POLL_MILLIS = 200L;

    /**
     * The arbiter shared by every client of one endpoint.
     *
     * @param endpointKey endpoint identity (normally the Base URL); {@code null}/empty maps to one
     *                    shared default instance
     */
    public static RetryArbiter forEndpoint(String endpointKey) {
        String key = endpointKey == null || endpointKey.isEmpty() ? "<default>" : endpointKey;
        return SHARED.computeIfAbsent(key, RetryArbiter::new);
    }

    /** Drop every shared arbiter (test isolation; a live system should not need this). */
    public static void clearShared() {
        SHARED.clear();
    }

    /**
     * How many attempts may run concurrently while the endpoint is congested. The default of 1
     * implements "let the current request finish first, then take the next one"; raising it lets
     * the top-priority tier run in parallel when the endpoint's limit allows more than one call.
     */
    public volatile int maxConcurrentWhileCongested = 1;

    private final String name;
    private final ReentrantLock lock = new ReentrantLock();
    /** Signalled whenever a slot is handed out (or congestion lifts). Guarded by {@link #lock}. */
    private final Condition turnAvailable = lock.newCondition();

    /** Parked callers in priority order — head is the next one to admit. Guarded by {@link #lock}. */
    private final List<Waiter> waiting = new ArrayList<>();
    /** Attempts currently holding a slot (granted, not yet released). Guarded by {@link #lock}. */
    private int active;
    /** True from the first 429/5xx until an attempt succeeds with an empty queue. Guarded by {@link #lock}. */
    private boolean congested;
    /** Arrival counter: FIFO tiebreaker inside one retry tier. Guarded by {@link #lock}. */
    private long arrivals;

    // Diagnostics only (tests and log lines).
    private long parkedTotal;
    private long admittedTotal;
    private long throttledTotal;

    RetryArbiter(String name) {
        this.name = name == null || name.isEmpty() ? "<default>" : name;
    }

    /** Highest retry count first; inside one retry tier, earliest arrival first. */
    private static final Comparator<Waiter> PRIORITY =
            Comparator.comparingInt((Waiter w) -> w.retries).reversed()
                    .thenComparingLong(w -> w.arrival);

    /** One parked caller. */
    private static final class Waiter {
        final int retries;    // retries already performed → the priority key (higher wins)
        final long arrival;   // FIFO tiebreaker inside one retry tier
        boolean granted;

        Waiter(int retries, long arrival) {
            this.retries = retries;
            this.arrival = arrival;
        }
    }

    /**
     * A granted attempt slot. The caller must release it as soon as the attempt is over — and in
     * particular <em>before</em> its retry delay, so that its backoff does not hold the endpoint.
     */
    public final class Slot implements AutoCloseable {
        private final long waitedMillis;   // how long this attempt was parked (diagnostics)
        private boolean released;

        private Slot(long waitedMillis) {
            this.waitedMillis = waitedMillis;
        }

        /** How long the request had to wait for its turn before this attempt was sent. */
        public long waitedMillis() {
            return waitedMillis;
        }

        /**
         * Give the slot back.
         *
         * @param succeeded whether the attempt returned a normal response; a success with an empty
         *                  queue marks the endpoint as no longer congested
         */
        public void release(boolean succeeded) {
            if (released) {
                return;
            }
            released = true;
            RetryArbiter.this.release(succeeded);
        }

        /** Release without claiming success (safe default: keeps congestion in place). */
        @Override
        public void close() {
            release(false);
        }
    }

    // ── Public gate API ────────────────────────────────────

    /**
     * Take a slot for one attempt.
     *
     * <p>Outside congestion this returns immediately and imposes no limit. While congested it
     * parks the caller until every caller with a higher retry count has been served (and, at
     * equal retry counts, until the earlier arrivals have been served).
     *
     * <p>The parked caller keeps its place in the queue for the whole wait: {@code keepWaiting}
     * is re-checked every {@code pollMillis} so that it can abandon its turn when its own abort
     * condition fires (a paused system, a shutdown) without losing its FIFO position in the
     * meantime. The predicate is evaluated while the arbiter lock is held, so it must be cheap
     * and must not block.
     *
     * @param retries       retries already performed by this request (0 on the first attempt)
     * @param pollMillis    how often {@code keepWaiting} is re-checked
     * @param keepWaiting   abort condition; {@code null} means "wait indefinitely". The wait also
     *                      ends when the thread is interrupted.
     * @return the slot to release after the attempt, or {@code null} when the caller gave up its
     *         turn (abort condition or interrupt) — it must not send the request in that case
     */
    public Slot acquire(int retries, long pollMillis, BooleanSupplier keepWaiting) {
        long poll = Math.max(1L, pollMillis);
        lock.lock();
        try {
            // Fast path — the endpoint is healthy: grant at once, no queueing, no concurrency cap.
            if (!congested) {
                active++;
                admittedTotal++;
                return new Slot(0L);
            }
            Waiter me = new Waiter(retries, ++arrivals);
            waiting.add(me);
            waiting.sort(PRIORITY);
            parkedTotal++;
            maybeAdmit();
            long parkedAt = System.currentTimeMillis();
            while (!me.granted) {
                if (keepWaiting != null && !keepWaiting.getAsBoolean()) {
                    // Give up the turn: a caller that aborts must never hold back the queue.
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
            admittedTotal++;
            return new Slot(System.currentTimeMillis() - parkedAt);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Report a retryable API error (HTTP 429 / 5xx) observed by one attempt: from now on the
     * endpoint is congested and attempts are ordered by retry count until a success clears it.
     */
    public void throttled(String cause) {
        lock.lock();
        try {
            throttledTotal++;
            if (!congested) {
                congested = true;
                logger.info("{} is throttling us ({}): ordering attempts by retry count — "
                        + "the most-retried request finishes first, the others follow in order",
                        name, cause);
            }
        } finally {
            lock.unlock();
        }
    }

    private void release(boolean succeeded) {
        lock.lock();
        try {
            if (active > 0) {
                active--;
            }
            if (succeeded && waiting.isEmpty() && active == 0) {
                congested = false;
                logger.debug("{} answered normally and no caller is parked — congestion lifted", name);
            }
            maybeAdmit();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Hand out slots to the highest-priority parked callers. Caller holds {@link #lock}.
     *
     * <p>Invariant: callers only ever park while congested, and congestion is only cleared with an
     * empty queue — so the defensive {@code !congested} branch below cannot trigger today. It is
     * kept so that a future change to {@link #release} cannot silently strand a parked queue.
     */
    private void maybeAdmit() {
        if (waiting.isEmpty()) {
            return;
        }
        if (!congested) {
            for (Waiter w : waiting) {
                w.granted = true;
                active++;
            }
            waiting.clear();
            turnAvailable.signalAll();
            return;
        }
        int cap = Math.max(1, maxConcurrentWhileCongested);
        boolean admitted = false;
        while (!waiting.isEmpty() && active < cap) {
            Waiter next = waiting.remove(0);   // highest retry count, then earliest arrival
            next.granted = true;
            active++;
            admitted = true;
        }
        if (admitted) {
            turnAvailable.signalAll();
        }
    }

    // ── Diagnostics ────────────────────────────────────────

    /** Whether the endpoint is currently throttling us and the ordering policy is engaged. */
    public boolean isCongested() {
        lock.lock();
        try {
            return congested;
        } finally {
            lock.unlock();
        }
    }

    /** How many callers are parked right now. */
    public int waitingCount() {
        lock.lock();
        try {
            return waiting.size();
        } finally {
            lock.unlock();
        }
    }

    /** How many attempts currently hold a slot. */
    public int activeCount() {
        lock.lock();
        try {
            return active;
        } finally {
            lock.unlock();
        }
    }

    /** Retry count of the caller that would be served next ({@code -1} when nobody is parked). */
    public int highestWaitingRetries() {
        lock.lock();
        try {
            return waiting.isEmpty() ? -1 : waiting.get(0).retries;
        } finally {
            lock.unlock();
        }
    }

    /** Endpoint identity this arbiter is shared under. */
    public String name() {
        return name;
    }

    /** Total callers that had to park, total slots granted, total throttle reports. */
    public long[] stats() {
        lock.lock();
        try {
            return new long[]{parkedTotal, admittedTotal, throttledTotal};
        } finally {
            lock.unlock();
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
