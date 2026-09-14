package com.agent.software.runtime;

/**
 * The clock's view of the team, implemented by {@link LifecycleCoordinator}.
 *
 * <p>Keeping this a narrow interface lets {@link ClockService} be tested with a
 * hand-written gate and keeps the clock free of role-management knowledge.
 */
public interface LifecycleGate {

    /** True while at least one role is executing a task. */
    boolean anyBusy();

    /** True when the whole team is idle (no task running, none queued). */
    boolean allIdle();

    /** True while the simulation is paused. */
    boolean paused();

    /** True once every role has wrapped up, so the day may roll over. */
    boolean rolloverReady();

    /** Invoked when the wrap-up grace period expires. */
    void forceWrapUp();
}
