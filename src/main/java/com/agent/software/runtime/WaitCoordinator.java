package com.agent.software.runtime;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Synchronous reply exchange for {@code talk(wait=true)}.
 *
 * <p>Extracted from the legacy {@code AgentRole} WAIT state machine so the state
 * machine itself can be unit tested without a role. {@link #begin} must be called
 * before the request is delivered: a reply that arrives between delivery and
 * {@code begin} would otherwise be lost.
 */
public final class WaitCoordinator {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private boolean waiting;
    private String waitingFor;
    private String reply;

    /** Enter the waiting state for {@code target}; returns false if already waiting. */
    public boolean begin(String target) {
        lock.lock();
        try {
            if (waiting) {
                return false;
            }
            waiting = true;
            waitingFor = target;
            reply = null;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Block until a reply is delivered/aborted or {@code timeout} elapses (null = forever). */
    public Optional<String> await(Duration timeout) {
        lock.lock();
        try {
            if (reply != null) {
                return Optional.of(reply);
            }
            if (timeout == null) {
                condition.await();
            } else {
                condition.await(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);
            }
            return Optional.ofNullable(reply);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    /** Deliver a real reply; returns false when nobody is waiting. */
    public boolean deliver(String content) {
        lock.lock();
        try {
            if (!waiting) {
                return false;
            }
            reply = content == null ? "" : content;
            condition.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Wake a waiting role with a synthetic message (shift end / wrap-up force). */
    public boolean abort(String message) {
        lock.lock();
        try {
            if (!waiting || reply != null) {
                return false;
            }
            reply = message == null ? "" : message;
            condition.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean isWaiting() {
        lock.lock();
        try {
            return waiting;
        } finally {
            lock.unlock();
        }
    }

    public String waitingFor() {
        lock.lock();
        try {
            return waitingFor;
        } finally {
            lock.unlock();
        }
    }

    /** Leave the waiting state and clear any stale reply. */
    public void end() {
        lock.lock();
        try {
            waiting = false;
            waitingFor = null;
            reply = null;
        } finally {
            lock.unlock();
        }
    }
}
