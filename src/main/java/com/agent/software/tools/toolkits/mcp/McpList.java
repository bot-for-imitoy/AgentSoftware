package com.agent.software.tools.toolkits.mcp;

import com.agent.software.computers.Computer;
import com.agent.software.role.AgentRole;

import com.agent.software.role.ToolRegistry.ToolDef;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mcp_list — 列出本地全部可用的 MCP 工具 (名称 + 简述).
 */
public class McpList extends Tool {

    private final AgentRole agentRole;

    public McpList(AgentRole agentRole) {
        super();
        this.agentRole = agentRole;
    }

    @Override
    public String getToolName() {
        return "mcp_list";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String handler(Map<String, Object> args) {
        Computer computer = agentRole.computer();
        List<String> avail = new ArrayList<>();
        for (ToolDef td : computer.iterMcpTools()) {
            avail.add("- " + td.name + ": " + td.description);
        }
        if (avail.isEmpty()) {
            return "mcp_list: no MCP server tools on this computer (the server may not be connected).";
        }
        List<String> out = new ArrayList<>();
        out.add("mcp_list: this computer's MCP server has " + avail.size() + " tools:");
        out.addAll(avail);
        return String.join("\n", out);
    }
}
