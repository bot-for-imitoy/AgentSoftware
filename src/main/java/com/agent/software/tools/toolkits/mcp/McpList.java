package com.agent.software.tools.toolkits.mcp;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** mcp_list：列出当前电脑上 MCP 服务器暴露的工具。 */
public class McpList extends Tool {

    private final Role role;
    private final MCPManager manager;

    public McpList(Role role, MCPManager manager) {
        this.role = role;
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "mcp_list";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String getDescription() {
        return "List MCP tools exposed by the server running on your computer.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || !role.hasComputer()) {
            return "mcp_list: no computer";
        }
        var tools = role.getComputer().getMcpTools();
        StringBuilder sb = new StringBuilder("mcp_list: " + tools.size() + " tool(s)\n");
        for (var t : tools) {
            sb.append("  - ").append(t.getToolName()).append(": ").append(t.getDescription()).append('\n');
        }
        if (tools.isEmpty()) {
            sb.append("  (none; the server may not be started yet)");
        }
        return sb.toString();
    }
}
