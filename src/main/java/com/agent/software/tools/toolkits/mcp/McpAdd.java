package com.agent.software.tools.toolkits.mcp;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** mcp_add：把一个 MCP 工具装到自己的电脑上。 */
public class McpAdd extends Tool {

    private final Role role;
    private final MCPManager manager;

    public McpAdd(Role role, MCPManager manager) {
        this.role = role;
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "mcp_add";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("tool", "MCP tool name to install");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Install an MCP tool onto your own computer.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        String name = args.get("tool") == null ? "" : String.valueOf(args.get("tool"));
        return manager.addTool(role, name);
    }
}
