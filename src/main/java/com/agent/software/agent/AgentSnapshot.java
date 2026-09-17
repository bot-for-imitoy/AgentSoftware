package com.agent.software.agent;

import com.agent.software.kernel.Ids.RoleId;

/** 单个角色的只读状态快照（供 Web / 控制台展示）。 */
public record AgentSnapshot(RoleId id, String name, AgentState state, boolean busy,
                            int queueDepth, String currentTask) {
}
