package com.agent.software.ports;

import com.agent.software.domain.TaskStatus;
import com.agent.software.kernel.RoleId;
import com.agent.software.kernel.TaskId;

/**
 * Observability sink for a role's work: chain of thought, tool calls and final
 * answers.
 *
 * <p>Replaces the historical {@code AgentRole.recordXxx} methods that wrote
 * directly into the Web chat store.
 */
public interface TracePort {

    void reason(RoleId role, int round, String text);

    void note(RoleId role, int round, String text);

    void tool(RoleId role, int round, ToolCall call, ToolResult result);

    void answer(RoleId role, TaskId task, TaskStatus status, int tokens, String text);

    /** A system-level notice (pause/resume/shutdown). */
    void notice(String text);
}
