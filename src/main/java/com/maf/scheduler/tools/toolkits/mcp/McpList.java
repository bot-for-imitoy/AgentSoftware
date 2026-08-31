package com.maf.scheduler.tools.toolkits.mcp;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.Computer;
import com.maf.scheduler.core.ToolRegistry.ToolDef;
import com.maf.scheduler.tools.Tool;

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
            return "mcp_list: 本电脑暂无 MCP 服务器工具 (服务器可能未连接).";
        }
        List<String> out = new ArrayList<>();
        out.add("mcp_list: 本电脑 MCP 服务器共有 " + avail.size() + " 个工具:");
        out.addAll(avail);
        return String.join("\n", out);
    }
}
