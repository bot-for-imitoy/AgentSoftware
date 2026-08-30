package com.maf.scheduler.tools.toolkits.mcp;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.tools.MCPManagerToolkit;
import com.maf.scheduler.tools.Toolkit;

/**
 * MCP 工具管理类 (McpManager Toolkit) — 搜索/添加/移除本地 MCP 工具:
 * mcp_search / mcp_list / mcp_add / mcp_remove / mcp_my_tools.
 *
 * 底层复用全局共享的 {@link MCPManagerToolkit.MCPManager} (每角色电脑上
 * 运行独立的 MCP filesystem 服务器).
 */
public class McpManager extends Toolkit {

    private final AgentRole agentRole;
    private final MCPManagerToolkit.MCPManager manager;

    public McpManager(AgentRole agentRole, MCPManagerToolkit.MCPManager manager) {
        this.agentRole = agentRole;
        this.manager = manager;
        addTool(new McpSearch(agentRole));
        addTool(new McpList(agentRole));
        addTool(new McpAdd(agentRole, manager));
        addTool(new McpRemove(agentRole, manager));
        addTool(new McpMyTools(agentRole, manager));
    }

    public McpManager(AgentRole agentRole) {
        this(agentRole, new MCPManagerToolkit.MCPManager());
    }

    @Override
    public String getDescription(){
        return "MCP 工具管理: 搜索/列出/添加/移除本地 MCP 工具, 查看已添加的工具";
    }

}
