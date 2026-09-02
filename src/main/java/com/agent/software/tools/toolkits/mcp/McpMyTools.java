package com.agent.software.tools.toolkits.mcp;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mcp_my_tools - view the list of MCP tools already added to the current role.
 */
public class McpMyTools extends Tool {

    private final AgentRole agentRole;
    private final MCPManager manager;

    public McpMyTools(AgentRole agentRole, MCPManager manager) {
        super();
        this.agentRole = agentRole;
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
    public String handler(Map<String, Object> args) {
        List<Map<String, String>> mine = this.manager.listRoleTools(agentRole);
        if (mine.isEmpty()) {
            return "mcp_my_tools: you have not added any MCP tools yet. Use mcp_search / mcp_list to find tools and mcp_add to add them.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("mcp_my_tools: you have added " + mine.size() + " MCP tools:");
        for (Map<String, String> m : mine) {
            lines.add("- " + m.get("name") + ": " + m.get("description"));
        }
        return String.join("\n", lines);
    }
}
