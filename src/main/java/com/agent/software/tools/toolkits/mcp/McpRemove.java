package com.agent.software.tools.toolkits.mcp;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * mcp_remove - remove an already-added MCP tool from the current role.
 */
public class McpRemove extends Tool {

    private final Role role;
    private final MCPManager manager;

    public McpRemove(Role role, MCPManager manager) {
        super();
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
        schema.put("tool_name", "The MCP tool name to remove.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object oname = args.get("tool_name");
        if (!(oname instanceof String)) {
            return oname == null
                    ? "mcp_remove: Error: needs tool_name"
                    : "mcp_remove: Error: tool_name is not a string";
        }
        String name = ((String) oname).strip();
        if (name.isEmpty()) {
            return "mcp_remove: Error: needs tool_name";
        }
        return this.manager.removeTool(role, name);
    }
}
