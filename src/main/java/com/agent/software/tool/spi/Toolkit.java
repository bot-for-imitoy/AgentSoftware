package com.agent.software.tool.spi;

import java.util.List;

/**
 * 工具包：把一组工具绑定到它真正需要的能力端口上，依赖在构造期注入、无 role/system 穿透。
 */
public interface Toolkit {

    /** 工具包标识（对应 {@code RoleSpec.toolkits} 中的名字），例："note" / "todo" / "talk"。 */
    String id();

    /** 实例化本包暴露的全部工具（每角色一份，依赖在构造期注入）。 */
    List<Tool> instantiate();
}
