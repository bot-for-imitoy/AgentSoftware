package com.agent.software.tools.toolkits.mcp;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** mcp_search：在规则目录里搜索可用 MCP 工具。 */
public class McpSearch extends Tool {

    private final Role role;
    private final MCPManager manager;

    public McpSearch(Role role, MCPManager manager) {
        this.role = role;
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "mcp_search";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("query", "keyword to search MCP tools");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Search MCP tools by keyword; install them with mcp_add.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        String query = args.get("query") == null ? "" : String.valueOf(args.get("query"));
        List<Map<String, String>> found = manager.search(query);
        return "mcp_search: " + found.size() + " result(s)\n" + Json.stringify(found);
    }
}
