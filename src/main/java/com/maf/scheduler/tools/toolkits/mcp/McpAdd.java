package com.maf.scheduler.tools.toolkits.mcp;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.tools.toolkits.mcp.MCPManager;
import com.maf.scheduler.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * mcp_add — 为当前角色添加一个本地已有的 MCP 工具.
 * 添加后即可在后续任务中直接调用该工具.
 */
public class McpAdd extends Tool {

    private final AgentRole agentRole;
    private final MCPManager manager;

    public McpAdd(AgentRole agentRole, MCPManager manager) {
        super();
        this.agentRole = agentRole;
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "mcp_add";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("tool_name", "The MCP tool name to add, e.g. read_file.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object oname = args.get("tool_name");
        if (!(oname instanceof String)) {
            return oname == null
                    ? "mcp_add: Error: needs tool_name"
                    : "mcp_add: Error: tool_name is not a string";
        }
        String name = ((String) oname).strip();
        if (name.isEmpty()) {
            return "mcp_add: Error: needs tool_name";
        }
        return this.manager.addTool(agentRole, name);
    }
}
