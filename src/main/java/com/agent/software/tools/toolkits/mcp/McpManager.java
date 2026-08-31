package com.agent.software.tools.toolkits.mcp;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Toolkit;

/**
 * MCP 工具管理类 (McpManager Toolkit) — 搜索/添加/移除本地 MCP 工具:
 * mcp_search / mcp_list / mcp_add / mcp_remove / mcp_my_tools.
 *
 * 底层复用全局共享的 {@link MCPManager} (每角色电脑上
 * 运行独立的 MCP filesystem 服务器).
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
