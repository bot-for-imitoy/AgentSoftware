package com.agent.software.tools.toolkits.mcp;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** mcp_remove：从自己的电脑上卸载一个 MCP 工具。 */
public class McpRemove extends Tool {

    private final Role role;
    private final MCPManager manager;

    public McpRemove(Role role, MCPManager manager) {
        this.role = role;
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "mcp_remove";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("tool", "MCP tool name to remove");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Remove an MCP tool from your own computer.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        String name = args.get("tool") == null ? "" : String.valueOf(args.get("tool"));
        return manager.removeTool(role, name);
    }
}
