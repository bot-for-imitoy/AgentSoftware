package com.agent.software.tools.builtin;

import com.agent.software.ports.McpBridge;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.ToolContext;
import com.agent.software.tools.spi.Toolkit;

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
    public List<Tool> instantiate(ToolContext context) {
        throw new UnsupportedOperationException("skeleton");
    }
}
