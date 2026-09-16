package com.agent.software.tools.spi;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.ports.AgentControl;
import com.agent.software.ports.AgentTasks;
import com.agent.software.ports.Shell;

/**
 * 装配 Toolbox 时传给工具包的窄上下文：只含随单个 agent 变化的每角色能力。
 *
 * <p>共享能力（NoteBook、Mailbox、Clock 等）在构造工具包时注入，不进入本上下文。
 */
public record ToolContext(RoleId agent, Shell shell, AgentTasks tasks, AgentControl control) {
}
