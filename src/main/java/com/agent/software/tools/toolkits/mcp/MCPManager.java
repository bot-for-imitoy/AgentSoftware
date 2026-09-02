package com.agent.software.tools.toolkits.mcp;

import com.agent.software.computers.Computer;
import com.agent.software.role.AgentRole;


import com.agent.software.role.ToolRegistry.ToolDef;
import com.agent.software.utils.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tool manager - manages the MCP server tools on each role's computer
 * (originally MCPManagerToolkit.MCPManager; moved into the template-style package when the old toolkit class was removed).
 *
 * Architecture (plan C): each role's computer (podman container) runs an independent MCP filesystem
 * server, with the authorized directory being the container working directory. MCPManager keeps no global
 * tool pool; all queries/installs are based on the current role's own computer server.
 */
public final class MCPManager {

    private static final Logger logger = LoggerFactory.getLogger(MCPManager.class);

    private final Map<String, Set<String>> roleTools = new LinkedHashMap<>();

    /** Load the MCP group rules JSON (classpath: /mcp_group_rules.json). */
    public static Map<String, Object> loadRules() {
        try (InputStream in = MCPManager.class.getResourceAsStream("/mcp_group_rules.json")) {
            if (in == null) {
                throw new IllegalStateException("MCP group rules file not found: mcp_group_rules.json");
            }
            return Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read MCP group rules", e);
        }
    }

    /** Return whether a tool name matches the group rules (supports wildcard *). */
    public static boolean matchGroup(String toolName, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pat : patterns) {
            if (globMatch(toolName, pat)) {
                return true;
            }
        }
        return false;
    }

    private static boolean globMatch(String name, String pattern) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return name.matches(regex);
    }

    /**
     * Install the MCP tools of the given group to the role as default tools (from the role computer's independent server).
     * Returns the list of tool names that were installed successfully.
     */
    public List<String> installGroupDefaults(AgentRole role, String group) {
        // 1) Make sure the role computer's independent MCP server is installed
        Computer computer = role.computer();
        computer.installMcpServer();
        List<String> installed = computer.listInstalledMcpTools();
        if (installed.isEmpty()) {
            logger.warn("[{}] computer has no MCP server tools, skipping default group '{}'", role.roleId, group);
            return new ArrayList<>();
        }
        // 2) Filter according to the group rules
        List<String> patterns = new ArrayList<>();
        Map<String, Object> rules = loadRules();
        Object groups = rules.get("groups");
        if (groups instanceof List) {
            for (Object gObj : (List<?>) groups) {
                if (gObj instanceof Map<?, ?> g
                        && group.equals(String.valueOf(g.get("name")))) {
                    Object match = g.get("match");
                    if (match instanceof List) {
                        for (Object m : (List<?>) match) {
                            patterns.add(String.valueOf(m));
                        }
                    }
                    break;
                }
            }
        }
        List<String> targets = new ArrayList<>();
        for (String n : installed) {
            if (patterns.isEmpty() || matchGroup(n, patterns)) {
                targets.add(n);
            }
        }
        // 3) Install to the role one by one
        List<String> ok = new ArrayList<>();
        for (String name : targets) {
            try {
                String r = addTool(role, name);
                if (!r.startsWith("Error:")) {
                    ok.add(name);
                }
            } catch (Exception e) {
                logger.error("[{}] failed to install MCP default tool: {}", role.roleId, name, e);
            }
        }
        logger.info("[{}] MCP default tool group '{}' installed {} tools: {}",
                role.roleId, group, ok.size(), ok);
        return ok;
    }

    /** Install an MCP tool for the role (from the role computer's independent MCP server). */
    public String addTool(AgentRole role, String toolName) {
        Computer computer = role.computer();
        computer.installMcpServer();
        String roleId = role.roleId;
        Set<String> mine = roleTools.computeIfAbsent(roleId, k -> new LinkedHashSet<>());
        if (mine.contains(toolName)) {
            return "Tool '" + toolName + "' is already added to " + roleId + ", no need to add it again.";
        }
        ToolDef td = computer.getMcpTool(toolName);
        if (td == null) {
            return "Error: no tool named '" + toolName + "' exists on the MCP server of computer [" + roleId + "]. "
                    + "Installed on this computer: " + (computer.listInstalledMcpTools().isEmpty()
                    ? "(none)" : computer.listInstalledMcpTools())
                    + ". Use mcp_search / mcp_list to see all available tools.";
        }
        // The role registers a proxy handler -> forwards to the computer server for execution
        Computer compRef = computer;
        String tname = toolName;
        role.addSingleTool(td.name, td.description, td.inputSchema,
                args -> compRef.runMcpTool(tname, args), td.source);
        mine.add(toolName);
        logger.info("[{}] MCP tool installed to computer: {} (source {})", roleId, toolName, td.source);
        return "Success: tool '" + toolName + "' installed to " + roleId
                + " (" + truncate(td.description, 60) + ")";
    }

    /** Uninstall an MCP tool from the role's computer. */
    public String removeTool(AgentRole role, String toolName) {
        String roleId = role.roleId;
        Set<String> mine = roleTools.getOrDefault(roleId, new LinkedHashSet<>());
        if (!mine.contains(toolName)) {
            return "Tool '" + toolName + "' has not been added to " + roleId + ", nothing to remove.";
        }
        Computer computer = role.computer();
        computer.uninstallMcpTool(toolName);
        role.removeSingleTool(toolName);
        mine.remove(toolName);
        logger.info("[{}] MCP tool uninstalled from computer: {}", roleId, toolName);
        return "Success: tool '" + toolName + "' has been uninstalled from the computer of " + roleId + ".";
    }

    /** List the MCP tools installed on the role's computer. */
    public List<Map<String, String>> listRoleTools(AgentRole role) {
        Computer computer = role.computer();
        List<Map<String, String>> out = new ArrayList<>();
        for (String n : computer.listInstalledMcpTools()) {
            ToolDef td = computer.getMcpTool(n);
            if (td == null) {
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", n);
            m.put("description", truncate(td.description, 120));
            out.add(m);
        }
        return out;
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }
}
