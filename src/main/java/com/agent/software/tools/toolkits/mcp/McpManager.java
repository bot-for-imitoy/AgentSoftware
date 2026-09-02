package com.agent.software.tools.toolkits.mcp;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Toolkit;

/**
 * MCP tool management toolkit (McpManager Toolkit) — search/add/remove local MCP tools:
 * mcp_search / mcp_list / mcp_add / mcp_remove / mcp_my_tools.
 *
 * Under the hood it reuses the global shared {@link MCPManager} (an independent
 * MCP filesystem server runs on each role's computer).
 */
public class McpManager extends Toolkit {

    private final AgentRole agentRole;
    private final MCPManager manager;

    public McpManager(AgentRole agentRole, MCPManager manager) {
        this.agentRole = agentRole;
        this.manager = manager;
        addTool(new McpSearch(agentRole));
        addTool(new McpList(agentRole));
        addTool(new McpAdd(agentRole, manager));
        addTool(new McpRemove(agentRole, manager));
        addTool(new McpMyTools(agentRole, manager));
    }

    public McpManager(AgentRole agentRole) {
        this(agentRole, new MCPManager());
    }

    @Override
    public String getDescription(){
        return "MCP tool management: search/list/add/remove local MCP tools and view added tools";
    }

}
