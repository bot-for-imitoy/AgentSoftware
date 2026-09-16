package com.agent.software.engine;

import com.agent.software.ports.Transcript;

/**
 * 单角色常驻工作循环（从 master {@code RolePool.roleLoop} 抽出）。
 *
 * <p>有序的等待链：全局暂停 → 可执行队列取任务 → 交给 {@link ToolLoop} →
 * 落结果 → 写轨迹。{@link Agent} 在 {@code start()} 时创建它，但自身不持有它，
 * 因此对象图里没有环。
 */
public final class TaskRunner implements Runnable {

    private final Agent agent;
    private final ToolLoop toolLoop;
    private final Transcript transcript;
    private final LifecycleGate gate;

    public TaskRunner(Agent agent, ToolLoop toolLoop, Transcript transcript, LifecycleGate gate) {
        this.agent = agent;
        this.toolLoop = toolLoop;
        this.transcript = transcript;
        this.gate = gate;
    }

    @Override
    public void run() {
        throw new UnsupportedOperationException("skeleton");
    }

    public void requestStop() {
        throw new UnsupportedOperationException("skeleton");
    }
}
