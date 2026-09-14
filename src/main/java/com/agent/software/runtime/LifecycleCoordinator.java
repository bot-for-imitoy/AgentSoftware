package com.agent.software.runtime;

import com.agent.software.domain.AgentState;
import com.agent.software.ports.TracePort;

/**
 * Owns the pause flag, the day-rollover gate and the wrap-up force.
 *
 * <p>Replaces the callbacks the legacy {@code AgentSystem} installed into
 * {@code TimeEventBus}; the clock now asks this coordinator through
 * {@link LifecycleGate} instead of receiving wired lambdas.
 */
public final class LifecycleCoordinator implements LifecycleGate {

    private static final String ABORT_SHIFT_END =
            "[System: the shift ended and the colleague you were waiting for has gone off duty. "
                    + "Treat this as their reply for now, finish up and write today's summary.]";
    private static final String ABORT_WRAP_UP =
            "[System: the daily wrap-up deadline passed. Treat this as the reply for now, "
                    + "finish up and rest; leftover work resumes at the next shift.]";

    private final TeamRuntime team;
    private final TracePort trace;

    private volatile boolean paused;
    private volatile String pauseReason = "";

    public LifecycleCoordinator(TeamRuntime team, TracePort trace) {
        this.team = team;
        this.trace = trace;
    }

    // ── LifecycleGate ──────────────────────────────────────────────────

    @Override
    public boolean anyBusy() {
        for (AgentRuntime r : team.all()) {
            if (r.isBusy()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean allIdle() {
        if (team.size() == 0) {
            return false;
        }
        for (AgentRuntime r : team.all()) {
            if (r.isBusy() || r.queueDepth() > 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean paused() {
        return paused;
    }

    @Override
    public boolean rolloverReady() {
        if (team.size() == 0) {
            return false;
        }
        for (AgentRuntime r : team.all()) {
            if (r.state() != AgentState.OFF_DUTY) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void forceWrapUp() {
        for (AgentRuntime r : team.all()) {
            if (r.waits().isWaiting()) {
                r.waits().abort(ABORT_WRAP_UP);
            }
        }
        for (AgentRuntime r : team.all()) {
            if (r.state() != AgentState.OFF_DUTY && !r.isBusy()) {
                r.setState(AgentState.OFF_DUTY);
            }
        }
    }

    // ── shift hooks ────────────────────────────────────────────────────

    /** At shift start every role goes back on duty. */
    public void onShiftStart() {
        for (AgentRuntime r : team.all()) {
            if (r.state() != AgentState.IDLE && r.state() != AgentState.WAITING) {
                r.setState(AgentState.IDLE);
            }
        }
    }

    /** At shift end, unblock anybody synchronously waiting for a colleague. */
    public void onShiftEnd() {
        for (AgentRuntime r : team.all()) {
            if (r.waits().isWaiting()) {
                r.waits().abort(ABORT_SHIFT_END);
            }
        }
    }

    // ── pause / resume ─────────────────────────────────────────────────

    public void pause(String reason) {
        boolean transition = !paused;
        paused = true;
        if (reason != null && !reason.isBlank()) {
            pauseReason = reason;
        }
        if (transition && trace != null) {
            trace.notice("\u23f8 System paused" + (pauseReason.isEmpty() ? "" : " \u2014 " + pauseReason));
        }
    }

    public void resume() {
        boolean transition = paused;
        paused = false;
        pauseReason = "";
        if (transition && trace != null) {
            trace.notice("\u25b6 System resumed");
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public String pauseReason() {
        return pauseReason;
    }
}
