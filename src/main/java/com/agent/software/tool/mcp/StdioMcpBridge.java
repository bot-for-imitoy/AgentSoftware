package com.agent.software.tool.mcp;

import com.agent.software.infra.config.AppPaths;
import com.agent.software.kernel.Ids.RoleId;

import java.util.List;
import com.agent.software.tool.mcp.McpBridge.McpToolInfo;

/**
 * stdio JSON-RPC 形态的 MCP 工具桥：按角色安装/卸载工具并转发调用。
 */
public final class StdioMcpBridge implements McpBridge {

    /** 绑定数据路径（MCP 安装状态与日志落盘位置）。 */
    public StdioMcpBridge(AppPaths paths) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<McpToolInfo> search(String keyword) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<McpToolInfo> installed(RoleId agent) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void install(RoleId agent, String toolName) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void uninstall(RoleId agent, String toolName) {
        throw new UnsupportedOperationException("skeleton");
    }
}
