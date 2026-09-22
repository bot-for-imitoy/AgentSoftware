package com.agent.software.tools.toolkits.mcp;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** mcp_my_tools：查看自己已安装的 MCP 工具。 */
public class McpMyTools extends Tool {

    private final Role role;
    private final MCPManager manager;

    public McpMyTools(Role role, MCPManager manager) {
        this.role = role;
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "mcp_my_tools";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String getDescription() {
        return "Show the MCP tools already installed on your computer.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        return "mcp_my_tools: " + Json.stringify(manager.listRoleTools(role));
    }
}
