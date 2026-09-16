package com.agent.software.model;

import java.util.List;

/**
 * 单个角色的快照：定义 + 生命周期状态 + 队列/历史 + 当日对话。
 *
 * <p>master 把这些分散在 {@code StateStore.roleToDict} 与 {@code AgentRole} 的多个
 * public 字段里。
 */
public record RoleSnapshot(RoleSpec spec, AgentState state,
                           List<Task.TaskRecord> pending, List<Task.TaskRecord> history,
                           int conversationDay, List<Message> conversation) {
}
