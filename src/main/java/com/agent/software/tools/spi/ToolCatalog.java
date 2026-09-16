package com.agent.software.tools.spi;

import com.agent.software.model.RoleSpec;
import com.agent.software.ports.Toolbox;

import java.util.List;

/**
 * 工具包注册表：按 {@link RoleSpec#toolkits()} 选包并装配成该角色的 {@link Toolbox}，数据驱动、无 Java 分支。
 */
public final class ToolCatalog {

    /** 注册一个工具包，返回自身以便链式调用。 */
    public ToolCatalog register(Toolkit toolkit) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 已注册的全部工具包 id。 */
    public List<String> ids() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 选择 spec 指定的工具包并用上下文实例化，装配成一个 Toolbox。 */
    public Toolbox build(RoleSpec spec, ToolContext context) {
        throw new UnsupportedOperationException("skeleton");
    }
}
