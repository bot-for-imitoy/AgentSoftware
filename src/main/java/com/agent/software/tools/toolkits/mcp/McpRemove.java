package com.agent.software.tools.toolkits.mcp;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * mcp_remove — 从当前角色移除一个已添加的 MCP 工具.
 */
public class McpRemove extends Tool {

    private final AgentRole agentRole;
    private final MCPManager manager;

    public McpRemove(AgentRole agentRole, MCPManager manager) {
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
