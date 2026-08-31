package com.maf.scheduler.tools.toolkits.mcp;

import com.maf.scheduler.role.AgentRole;
import com.maf.scheduler.core.Computer;
import com.maf.scheduler.core.ToolRegistry.ToolDef;
import com.maf.scheduler.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mcp_search — 搜索本地已有的 MCP 工具 (按名称或描述关键词).
 * 先搜索找到合适的工具, 再用 mcp_add 添加给自己.
 */
public class McpSearch extends Tool {

    private final AgentRole agentRole;

    public McpSearch(AgentRole agentRole) {
        super();
        this.agentRole = agentRole;
    }

    @Override
    public String getToolName() {
        return "mcp_search";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("keyword", "Search keyword, e.g. file/git/issue/read.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object okeyword = args.get("keyword");
        if (!(okeyword instanceof String)) {
            return okeyword == null
                    ? "mcp_search: Error: needs keyword"
                    : "mcp_search: Error: keyword is not a string";
        }
        String kw = ((String) okeyword).strip().toLowerCase();
        if (kw.isEmpty()) {
            return "mcp_search: Error: needs keyword";
        }
        Computer computer = agentRole.computer();
        List<String> lines = new ArrayList<>();
        int hits = 0;
        for (String n : computer.listInstalledMcpTools()) {
            ToolDef td = computer.getMcpTool(n);
            if (td == null) {
                continue;
            }
            if (n.toLowerCase().contains(kw) || td.description.toLowerCase().contains(kw)) {
                lines.add("- " + n + ": " + td.description);
                hits++;
            }
        }
        if (hits == 0) {
            return "mcp_search: 没有匹配 '" + kw + "' 的 MCP 工具. 本电脑服务器已装: "
                    + (computer.listInstalledMcpTools().isEmpty() ? "(无)"
                    : computer.listInstalledMcpTools()) + ". 可用 mcp_list 查看全部.";
        }
        List<String> out = new ArrayList<>();
        out.add("mcp_search: 搜索 '" + kw + "' 找到 " + hits + " 个工具:");
        out.addAll(lines);
        return String.join("\n", out);
    }
}
