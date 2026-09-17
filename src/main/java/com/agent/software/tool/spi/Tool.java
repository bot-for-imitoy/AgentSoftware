package com.agent.software.tool.spi;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Payload;

/**
 * 单个工具：声明 {@link ToolSpec} 并以类型化 {@link Payload} 作为参数执行，替代裸 Map 参数。
 */
public interface Tool {

    /** 工具的对外声明（名字、描述、参数 schema）。 */
    ToolSpec spec();

    /** 以调用者角色身份执行工具，参数为类型化负载。 */
    ToolResult invoke(RoleId agent, Payload arguments);
}
