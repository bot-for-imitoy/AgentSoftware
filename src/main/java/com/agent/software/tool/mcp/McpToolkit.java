package com.agent.software.tool.mcp;

import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.Toolkit;

import java.util.List;

/**
 * MCP 管理工具包（id {@code "mcp_manager"}），暴露工具：mcp_search / mcp_list / mcp_add / mcp_remove / mcp_my_tools。
 */
public final class McpToolkit implements Toolkit {

    private final McpBridge mcp;

    public McpToolkit(McpBridge mcp) {
        this.mcp = mcp;
    }

    @Override
    public String id() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Tool> instantiate() {
        throw new UnsupportedOperationException("skeleton");
    }
}
