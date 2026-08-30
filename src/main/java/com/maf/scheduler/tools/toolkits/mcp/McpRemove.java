package com.maf.scheduler.tools.toolkits.mcp;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.tools.MCPManagerToolkit;
import com.maf.scheduler.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * mcp_remove — 从当前角色移除一个已添加的 MCP 工具.
 */
public class McpRemove extends Tool {

    private final AgentRole agentRole;
    private final MCPManagerToolkit.MCPManager manager;

    public McpRemove(AgentRole agentRole, MCPManagerToolkit.MCPManager manager) {
        super();
        this.agentRole = agentRole;
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
        return this.manager.removeTool(agentRole, name);
    }
}
