package com.agent.software.agent;

/**
 * 角色自控能力（少数会改变自身状态的工具使用，例如 take_rest / summary）。
 *
 * <p>它把"工具可以改角色状态"这件事限制在一个显式端口上；master 里工具是直接
 * 拿到 {@code AgentRole} 并写 public 字段的。
 */
public interface AgentControl {

    /** 迁移自身状态（由 {@code agent.AgentStateMachine} 校验合法性）。 */
    void transitionTo(AgentState next);

    /** 关闭第 day 天的对话（下班/总结完成后调用）。 */
    void closeDayConversation(int day);

    /** 关闭个人电脑（每日总结完成后调用）。 */
    void powerOffComputer();
}
