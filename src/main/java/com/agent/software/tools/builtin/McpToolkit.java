package com.agent.software.tools.builtin;

import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ComputerPort;
import com.agent.software.ports.ToolResult;
import com.agent.software.ports.ToolSpec;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.ToolService;
import com.agent.software.tools.spi.Toolkit;
import com.agent.software.tools.spi.Tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The {@code mcp} toolkit: roles install MCP tools exposed by their own computer
 * at runtime.
 */
public final class McpToolkit {

    private McpToolkit() {
    }

    public static Toolkit create(ToolService tools, Function<RoleId, Optional<ComputerPort>> computers) {
        Map<RoleId, Set<String>> added = new ConcurrentHashMap<>();

        Tool search = Tools.of("mcp_search", "Search the MCP tools available on your computer",
                JsonSchema.builder()
                        .required("keyword", JsonSchema.Property.string("Search keyword, e.g. file/git/browser."))
                        .build(),
                (role, call) -> {
                    String keyword = Tools.argStripped(call, "keyword").toLowerCase(java.util.Locale.ROOT);
                    if (keyword.isEmpty()) {
                        return ToolResult.error("mcp_search: Error: needs keyword");
                    }
                    Optional<ComputerPort> computer = computers.apply(role);
                    if (computer.isEmpty()) {
                        return ToolResult.error("mcp_search: Error: no computer is assigned to " + role);
                    }
                    List<ToolSpec> hits = computer.get().mcpTools().stream()
                            .filter(spec -> spec.name().toLowerCase(java.util.Locale.ROOT).contains(keyword)
                                    || spec.description().toLowerCase(java.util.Locale.ROOT).contains(keyword))
                            .toList();
                    if (hits.isEmpty()) {
                        return ToolResult.success("mcp_search: no MCP tool matches '" + keyword + "'.");
                    }
                    return ToolResult.success("mcp_search: " + hits.size() + " tool(s):" + render(hits));
                });

        Tool list = Tools.of("mcp_list", "List every MCP tool available on your computer",
                JsonSchema.object(),
                (role, call) -> computers.apply(role)
                        .map(computer -> {
                            List<ToolSpec> specs = computer.mcpTools();
                            if (specs.isEmpty()) {
                                return ToolResult.success("mcp_list: this computer exposes no MCP tools.");
                            }
                            return ToolResult.success("mcp_list: " + specs.size() + " tool(s):" + render(specs));
                        })
                        .orElseGet(() -> ToolResult.error("mcp_list: no computer is assigned to " + role)));

        Tool add = Tools.of("mcp_add", "Install one MCP tool from your computer for your own use",
                JsonSchema.builder()
                        .required("tool_name", JsonSchema.Property.string("The MCP tool name to add, e.g. read_file."))
                        .build(),
                (role, call) -> {
                    String name = Tools.argStripped(call, "tool_name");
                    if (name.isEmpty()) {
                        return ToolResult.error("mcp_add: Error: needs tool_name");
                    }
                    Optional<ComputerPort> computer = computers.apply(role);
                    if (computer.isEmpty()) {
                        return ToolResult.error("mcp_add: Error: no computer is assigned to " + role);
                    }
                    if (tools.hasTool(role, name)) {
                        return ToolResult.success("mcp_add: tool '" + name + "' is already available.");
                    }
                    Optional<ToolSpec> spec = computer.get().mcpTools().stream()
                            .filter(candidate -> candidate.name().equals(name))
                            .findFirst();
                    if (spec.isEmpty()) {
                        return ToolResult.error("mcp_add: Error: no MCP tool named '" + name
                                + "' on this computer. Use mcp_search/mcp_list first.");
                    }
                    tools.addTool(role, spec.get(), (r, c) -> computers.apply(r)
                            .map(pc -> pc.callMcpTool(name, c.arguments()))
                            .orElseGet(() -> ToolResult.error("no computer is assigned to " + r)));
                    added.computeIfAbsent(role, k -> ConcurrentHashMap.newKeySet()).add(name);
                    return ToolResult.success("mcp_add: tool '" + name + "' installed.");
                });

        Tool remove = Tools.of("mcp_remove", "Uninstall one MCP tool you previously added",
                JsonSchema.builder()
                        .required("tool_name", JsonSchema.Property.string("The MCP tool name to remove."))
                        .build(),
                (role, call) -> {
                    String name = Tools.argStripped(call, "tool_name");
                    if (name.isEmpty()) {
                        return ToolResult.error("mcp_remove: Error: needs tool_name");
                    }
                    Set<String> mine = added.get(role);
                    boolean tracked = mine != null && mine.remove(name);
                    boolean existed = tools.removeTool(role, name);
                    if (!tracked && !existed) {
                        return ToolResult.error("mcp_remove: Error: tool '" + name + "' is not installed.");
                    }
                    return ToolResult.success("mcp_remove: tool '" + name + "' removed.");
                });

        Tool myTools = Tools.of("mcp_my_tools", "List the MCP tools you have installed",
                JsonSchema.object(),
                (role, call) -> {
                    Set<String> mine = added.get(role);
                    if (mine == null || mine.isEmpty()) {
                        return ToolResult.success("mcp_my_tools: you have not installed any MCP tools yet. "
                                + "Use mcp_search/mcp_list to find tools and mcp_add to install them.");
                    }
                    List<String> names = new ArrayList<>(mine);
                    names.sort(String::compareTo);
                    return ToolResult.success("mcp_my_tools: " + names.size() + " installed:" + renderNames(names));
                });

        return new Toolkit("mcp", "MCP tool management: search/list/add/remove MCP tools on your computer",
                List.of(search, list, add, remove, myTools));
    }

    private static String render(List<ToolSpec> specs) {
        return specs.stream()
                .map(spec -> "\n- " + spec.name() + ": " + spec.description())
                .collect(Collectors.joining());
    }

    private static String renderNames(List<String> names) {
        return names.stream().map(name -> "\n- " + name).collect(Collectors.joining());
    }
}
