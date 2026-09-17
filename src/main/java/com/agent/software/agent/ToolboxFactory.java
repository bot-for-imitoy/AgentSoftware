package com.agent.software.agent;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.tool.spi.Toolbox;

/**
 * 为一个角色装配 {@link Toolbox} 的工厂（实现放在 bootstrap）。
 *
 * <p>为什么不直接把 Toolbox 注入 {@link Agent} 的构造器：有一部分能力
 * （{@link AgentTasks}、{@link AgentControl}）由 {@link Agent} 自己实现，
 * 只有 Agent 存在之后才拿得到。用工厂把装配推迟到 {@link Agent#start()}，
 * 装配链上既没有环，也不需要可变的 setter 回填。
 */
@FunctionalInterface
public interface ToolboxFactory {

    /** 用该角色的定义与"自身"窄视图装配工具集合。 */
    Toolbox create(RoleSpec spec, AgentTasks tasks, AgentControl control);
}
