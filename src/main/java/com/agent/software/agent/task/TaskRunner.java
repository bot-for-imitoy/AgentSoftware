package com.agent.software.agent.task;

import com.agent.software.agent.AgentControl;
import com.agent.software.agent.AgentMailbox;
import com.agent.software.agent.AgentTasks;
import com.agent.software.agent.LifecycleGate;
import com.agent.software.transcript.Transcript;

/**
 * 单角色常驻工作循环（从 master {@code RolePool.roleLoop} 抽出）。
 *
 * <p>有序的等待链：全局暂停 → 可执行队列取任务 → 交给 {@link ToolLoop} →
 * 落结果 → 写轨迹。
 *
 * <p>注意它只认三个接口（{@link AgentTasks} / {@link AgentControl} /
 * {@link AgentMailbox}），不认识 {@code agent.Agent}：
 * master 的 {@code RolePool} 拿到了整个角色对象，于是"取任务"这件小事
 * 也能顺手改状态、读私账，环就是这么来的。
 */
public final class TaskRunner implements Runnable {

    private final AgentMailbox mailbox;
    private final AgentTasks tasks;
    private final AgentControl control;
    private final ToolLoop toolLoop;
    private final Transcript transcript;
    private final LifecycleGate gate;

    public TaskRunner(AgentMailbox mailbox, AgentTasks tasks, AgentControl control,
                      ToolLoop toolLoop, Transcript transcript, LifecycleGate gate) {
        this.mailbox = mailbox;
        this.tasks = tasks;
        this.control = control;
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
