package com.agent.software.company;

import com.agent.software.agent.LifecycleGate;
import com.agent.software.agent.Team;
import com.agent.software.sim.clock.Clock;
import com.agent.software.sim.clock.Tick;
import com.agent.software.sim.clock.TickObserver;

/**
 * 班次反应唯一处。
 *
 * <p>对应 master {@code AgentSystem.onTimeEvent} + {@code allRolesIdle} +
 * {@code dayRolloverReady} + {@code forceWrapUp} 这一堆散落逻辑：
 * <ul>
 *   <li>SHIFT_START：全员上岗、开机、提升暂存任务、装载当天日程；</li>
 *   <li>SHIFT_END：解阻塞等待中的角色、要求各自写总结（WRAPPING_UP）；</li>
 *   <li>forceWrapUp：收尾超时兜底，把仍未下班的角色强制 OFF_DUTY。</li>
 * </ul>
 */
public final class ShiftDirector implements TickObserver {

    private final Team team;
    private final Clock clock;
    private final LifecycleGate gate;

    public ShiftDirector(Team team, Clock clock, LifecycleGate gate) {
        this.team = team;
        this.clock = clock;
        this.gate = gate;
    }

    /** 时钟每步调用；内部只在班次边界动作。 */
    @Override
    public void onTick(Tick now) {
        throw new UnsupportedOperationException("skeleton");
    }

    public void onShiftStart() {
        throw new UnsupportedOperationException("skeleton");
    }

    public void onShiftEnd() {
        throw new UnsupportedOperationException("skeleton");
    }

    public void forceWrapUp() {
        throw new UnsupportedOperationException("skeleton");
    }
}
