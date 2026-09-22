package com.agent.software.tools.toolkits.mcp;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/** MCP 工具管理工具包：search / list / add / remove / my_tools。 */
public class McpManager extends Toolkit {

    private final Role role;
    private final MCPManager manager;

    public McpManager(Role role, MCPManager manager) {
        this.role = role;
        this.manager = manager == null ? new MCPManager() : manager;
        addTool(new McpSearch(role, this.manager));
        addTool(new McpList(role, this.manager));
        addTool(new McpAdd(role, this.manager));
        addTool(new McpRemove(role, this.manager));
        addTool(new McpMyTools(role, this.manager));
    }

    @Override
    public String getDescription() {
        return "MCP management: search/list/add/remove MCP tools on your own computer";
    }
}
