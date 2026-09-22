package com.agent.software.tools.toolkits.mcp;

import com.agent.software.role.Role;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具管理：按 {@code mcp_group_rules.json} 的工具组，把工具安装到角色自己的电脑上。
 *
 * <p>不保留进程级工具池：查询/安装都以当前角色的电脑为准。
 */
public final class MCPManager {

    private static final Logger logger = LoggerFactory.getLogger(MCPManager.class);
    private static final String RULES_RESOURCE = "/mcp_group_rules.json";

    private final Map<String, Set<String>> roleTools = new ConcurrentHashMap<>();

    /** 读取工具组规则（classpath 资源）。 */
    public static Map<String, Object> loadRules() {
        try (InputStream in = MCPManager.class.getResourceAsStream(RULES_RESOURCE)) {
            if (in == null) {
                return Map.of("servers", List.of(), "groups", List.of());
            }
            return Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("cannot read MCP rules", e);
            return Map.of("servers", List.of(), "groups", List.of());
        }
    }

    /** 规则里声明的 MCP 服务器包名。 */
    public static List<String> serverPackages() {
        List<String> out = new ArrayList<>();
        Object servers = loadRules().get("servers");
        if (servers instanceof List<?> list) {
            for (Object s : list) {
                if (s != null) {
                    out.add(String.valueOf(s));
                }
            }
        }
        return out;
    }

    /** 工具名是否匹配某个通配模式（支持 *）。 */
    public static boolean matchGroup(String toolName, List<String> patterns) {
        if (toolName == null || patterns == null) {
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
        if (pattern == null) {
            return false;
        }
        if (pattern.contains("*")) {
            String regex = pattern.replace(".", "\\.").replace("*", ".*");
            return name.matches(regex);
        }
        return name.equals(pattern);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groups() {
        List<Map<String, Object>> out = new ArrayList<>();
        Object g = loadRules().get("groups");
        if (g instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    /** 工具名 → 所属组名。 */
    private static String groupOf(String toolName) {
        for (Map<String, Object> group : groups()) {
            Object match = group.get("match");
            List<String> patterns = new ArrayList<>();
            if (match instanceof List<?> list) {
                for (Object p : list) {
                    patterns.add(String.valueOf(p));
                }
            }
            if (matchGroup(toolName, patterns)) {
                return String.valueOf(group.get("name"));
            }
        }
        return "default";
    }

    /** 规则里的全部工具（用于搜索）。 */
    public List<Map<String, String>> catalog() {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, Object> group : groups()) {
            String groupName = String.valueOf(group.get("name"));
            String description = group.get("description") == null ? "" : String.valueOf(group.get("description"));
            Object match = group.get("match");
            if (match instanceof List<?> list) {
                for (Object p : list) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("tool", String.valueOf(p));
                    m.put("group", groupName);
                    m.put("description", description);
                    out.add(m);
                }
            }
        }
        return out;
    }

    public List<Map<String, String>> search(String query) {
        String q = query == null ? "" : query.toLowerCase();
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> item : catalog()) {
            if (q.isBlank() || item.get("tool").toLowerCase().contains(q)
                    || item.get("group").toLowerCase().contains(q)) {
                out.add(item);
            }
        }
        return out;
    }

    /** 把某个组的默认工具装到角色电脑上。 */
    public List<String> installGroupDefaults(Role role, String group) {
        List<String> installed = new ArrayList<>();
        for (Map<String, String> item : catalog()) {
            if (group != null && group.equals(item.get("group"))) {
                addTool(role, item.get("tool"));
                installed.add(item.get("tool"));
            }
        }
        return installed;
    }

    public String addTool(Role role, String toolName) {
        if (role == null || toolName == null || toolName.isBlank()) {
            return "mcp_add error: missing role or tool name";
        }
        String group = groupOf(toolName);
        roleTools.computeIfAbsent(role.roleId, k -> new LinkedHashSet<>()).add(toolName);
        if (role.hasComputer()) {
            role.getComputer().installMcpTool(group, toolName);
        }
        return "mcp_add: installed '" + toolName + "' (group " + group + ") on " + role.roleId + "'s computer";
    }

    public String removeTool(Role role, String toolName) {
        if (role == null || toolName == null) {
            return "mcp_remove error: missing role or tool name";
        }
        Set<String> mine = roleTools.get(role.roleId);
        boolean removed = mine != null && mine.remove(toolName);
        if (role.hasComputer()) {
            role.getComputer().uninstallMcpTool(toolName);
        }
        return removed ? "mcp_remove: removed '" + toolName + "'" : "mcp_remove: '" + toolName + "' was not installed";
    }

    public List<Map<String, String>> listRoleTools(Role role) {
        List<Map<String, String>> out = new ArrayList<>();
        if (role == null) {
            return out;
        }
        for (String tool : roleTools.getOrDefault(role.roleId, Set.of())) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("tool", tool);
            m.put("group", groupOf(tool));
            out.add(m);
        }
        return out;
    }
}
