package com.agent.software.tools.toolkits.mcp;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/**
 * MCP tool management toolkit (McpManager Toolkit) — search/add/remove local MCP tools:
 * mcp_search / mcp_list / mcp_add / mcp_remove / mcp_my_tools.
 *
 * Under the hood it reuses the global shared {@link MCPManager} (an independent
 * MCP filesystem server runs on each role's computer).
 */
public class McpManager extends Toolkit {

    private final Role role;
    private final MCPManager manager;

    public McpManager(Role role, MCPManager manager) {
        this.role = role;
        this.manager = manager;
        addTool(new McpSearch(role));
        addTool(new McpList(role));
        addTool(new McpAdd(role, manager));
        addTool(new McpRemove(role, manager));
        addTool(new McpMyTools(role, manager));
    }

    public McpManager(Role role) {
        this(role, new MCPManager());
    }

    @Override
    public String getDescription(){
        return "MCP tool management: search/list/add/remove local MCP tools and view added tools";
    }

}
