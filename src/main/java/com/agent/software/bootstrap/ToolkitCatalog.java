package com.agent.software.bootstrap;

import com.agent.software.agent.AgentControl;
import com.agent.software.agent.AgentTasks;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.kernel.Payload;
import com.agent.software.tool.computer.Shell;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolbox;
import com.agent.software.tool.spi.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具目录：按 {@link RoleSpec#toolkits()} 选包，装配出该角色的 {@link Toolbox}。
 *
 * <p>每个角色一份。工具包在构造期就拿到自己需要的能力：共享能力（NoteBook / Mailbox /
 * Clock …）来自 {@link Deps} 之外的共享实例，每角色能力来自 {@link Deps}。
 * 因此 {@code tool.spi} 只依赖 {@code kernel}，不需要任何"工具上下文对象"。
 */
public final class ToolkitCatalog {

    private static final Logger logger = LoggerFactory.getLogger(ToolkitCatalog.class);

    /** 每个角色装配工具包时可见的能力集合。 */
    public record Deps(RoleSpec spec, Shell shell, AgentTasks tasks, AgentControl control) {
    }

    private final Map<String, Function<Deps, Toolkit>> factories = new LinkedHashMap<>();

    /** 注册一个工具包工厂；id 与 {@code RoleSpec.toolkits} 中的名字对应。 */
    public ToolkitCatalog register(String id, Function<Deps, Toolkit> factory) {
        if (id != null && !id.isBlank() && factory != null) {
            factories.put(id, factory);
        }
        return this;
    }

    /** 已注册的全部工具包 id。 */
    public List<String> ids() {
        return List.copyOf(factories.keySet());
    }

    /** 按 spec 指定的工具包逐个装配并合成一个 Toolbox。 */
    public Toolbox build(RoleSpec spec, Shell shell, AgentTasks tasks, AgentControl control) {
        Deps deps = new Deps(spec, shell, tasks, control);
        List<Tool> tools = new ArrayList<>();
        for (String id : spec.toolkits()) {
            Function<Deps, Toolkit> factory = factories.get(id);
            if (factory == null) {
                logger.warn("[{}] 未注册的工具包 {}，已跳过", spec.id().value(), id);
                continue;
            }
            try {
                Toolkit toolkit = factory.apply(deps);
                if (toolkit == null) {
                    continue;
                }
                List<Tool> instantiated = toolkit.instantiate();
                if (instantiated != null) {
                    tools.addAll(instantiated);
                }
            } catch (RuntimeException e) {
                logger.error("[{}] 装配工具包 {} 失败", spec.id().value(), id, e);
            }
        }
        return new AssembledToolbox(tools, spec.id());
    }

    /**
     * 由各工具包实例化出的工具合成的一个扁平工具箱。
     *
     * <p>{@link Toolbox#invoke(String, Payload)} 不带调用者身份，而
     * {@link Tool#invoke} 需要它——工具箱本来就是"某个角色的工具箱"（每角色一份），
     * 所以在装配时把角色 id 记下来。
     */
    private static final class AssembledToolbox implements Toolbox {

        private final Map<String, Tool> byName = new LinkedHashMap<>();
        private final com.agent.software.kernel.Ids.RoleId caller;

        private AssembledToolbox(List<Tool> tools, com.agent.software.kernel.Ids.RoleId caller) {
            this.caller = caller;
            for (Tool tool : tools) {
                String name = tool.spec().name();
                if (byName.putIfAbsent(name, tool) != null) {
                    logger.warn("工具名重复，后注册的 {} 被忽略", name);
                }
            }
        }

        @Override
        public List<ToolSpec> specs() {
            List<ToolSpec> out = new ArrayList<>(byName.size());
            for (Tool tool : byName.values()) {
                out.add(tool.spec());
            }
            return out;
        }

        @Override
        public ToolResult invoke(String toolName, Payload arguments) {
            Tool tool = toolName == null ? null : byName.get(toolName);
            if (tool == null) {
                return ToolResult.error("找不到工具：" + toolName);
            }
            return tool.invoke(caller, arguments == null ? Payload.empty() : arguments);
        }
    }
}
