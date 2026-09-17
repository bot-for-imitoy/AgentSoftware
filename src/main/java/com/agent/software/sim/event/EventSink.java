package com.agent.software.sim.event;

/**
 * 事件入口。
 *
 * <p>外部事件（客户/邮件/Web/时钟）都只通过 {@link #publish} 进入系统；
 * 由 {@code agent.dispatch.EventRouter} 实现。
 */
public interface EventSink {

    void publish(AgentEvent event);
}
