package com.agent.software.ports;

import com.agent.software.model.AgentEvent;

/**
 * 事件入口。
 *
 * <p>外部事件（客户/邮件/Web/时钟）都只通过 {@link #publish} 进入系统；
 * 由 {@code engine.EventRouter} 实现。
 */
public interface EventSink {

    void publish(AgentEvent event);
}
