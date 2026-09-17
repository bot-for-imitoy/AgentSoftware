package com.agent.software.bootstrap;

import com.agent.software.agent.AgentControl;
import com.agent.software.agent.AgentTasks;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.tool.computer.Shell;
import com.agent.software.tool.spi.Toolbox;
import com.agent.software.tool.spi.Toolkit;

import java.util.List;
import java.util.function.Function;

/**
 * 工具目录：按 {@link RoleSpec#toolkits()} 选包，装配出该角色的 {@link Toolbox}。
 *
 * <p>每个角色一份。工具包在构造期就拿到自己需要的能力：共享能力（NoteBook / Mailbox /
 * Clock …）来自 {@link Deps} 之外的共享实例，每角色能力来自 {@link Deps}。
 * 因此 {@code tool.spi} 只依赖 {@code kernel}，不需要任何"工具上下文对象"。
 */
public final class ToolkitCatalog {

    /** 每个角色装配工具包时可见的能力集合。 */
    public record Deps(RoleSpec spec, Shell shell, AgentTasks tasks, AgentControl control) {
    }

    /** 注册一个工具包工厂；id 与 {@code RoleSpec.toolkits} 中的名字对应。 */
    public ToolkitCatalog register(String id, Function<Deps, Toolkit> factory) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 已注册的全部工具包 id。 */
    public List<String> ids() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 按 spec 指定的工具包逐个装配并合成一个 Toolbox。 */
    public Toolbox build(RoleSpec spec, Shell shell, AgentTasks tasks, AgentControl control) {
        throw new UnsupportedOperationException("skeleton");
    }
}
