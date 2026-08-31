package com.agent.software.tools.toolkits.mcp;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mcp_my_tools — 查看当前角色已添加的 MCP 工具列表.
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
            return "mcp_my_tools: 你还没有添加任何 MCP 工具. 可用 mcp_search / mcp_list 寻找, 用 mcp_add 添加.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("mcp_my_tools: 你已添加 " + mine.size() + " 个 MCP 工具:");
        for (Map<String, String> m : mine) {
            lines.add("- " + m.get("name") + ": " + m.get("description"));
        }
        return String.join("\n", lines);
    }
}
