package com.agent.software.tool.mcp;

import com.agent.software.kernel.Ids.RoleId;

import java.util.List;

/**
 * MCP 工具桥（每台电脑各自安装的 MCP 工具集合）。
 *
 * <p>把 master 中 {@code MCPManager} + {@code Computer.mcpTools} + {@code MCPServer}
 * 三者纠缠的注册/调用逻辑收进一个端口，实现放在 {@code tool.mcp}。
 */
public interface McpBridge {

    /** 按关键词搜索可安装的 MCP 工具。 */
    List<McpToolInfo> search(String keyword);

    /** 某角色已安装的 MCP 工具。 */
    List<McpToolInfo> installed(RoleId agent);

    void install(RoleId agent, String toolName);

    void uninstall(RoleId agent, String toolName);

    record McpToolInfo(String name, String description) {
    }
}
