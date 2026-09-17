package com.agent.software.agent;

import com.agent.software.agent.dialog.ConversationMemory;

/**
 * 角色运行时视图：{@link Agent} 对执行器暴露的全部能力。
 *
 * <p>为什么把 {@link AgentTasks} + {@link AgentControl} 合成一个接口给
 * {@code agent.task.TaskRunner}：执行一次任务除了读写队列，还需要拿到
 * "本次任务的 System Prompt / 当日对话 / 今天第几天"，而这三样只存在于
 * {@link Agent} 内部。把它们塞进 {@link AgentTasks}（只读任务视图，工具也在用）
 * 会污染工具的可见面，所以单独开一个**面向执行器**的窄接口。
 *
 * <p>工具拿到的仍然是 {@link AgentTasks} + {@link AgentControl}（见
 * {@link ToolboxFactory}），看不到这里的方法。
 */
public interface AgentRuntime extends AgentTasks, AgentControl {

    /** 角色标识（执行器写轨迹 / 生成委派任务时要用）。 */
    com.agent.software.kernel.Ids.RoleId id();

    /** 本次任务使用的完整 System Prompt。 */
    String systemPrompt();

    /** 当日对话记忆。 */
    ConversationMemory conversation();

    /** 当前工作日（第几天）。 */
    int currentDay();
}
