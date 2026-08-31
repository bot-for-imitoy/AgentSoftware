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
 * MCP 工具管理器 — 管理每角色电脑上的 MCP 服务器工具
 * (原 MCPManagerToolkit.MCPManager, 随旧工具类删除迁入模板风格包).
 *
 * 架构 (C 方案): 每个角色的电脑 (podman 容器) 内运行独立的 MCP filesystem
 * 服务器, 授权目录 = 容器内工作目录. MCPManager 不维护全局工具池, 所有查询
 * /安装都基于当前角色自己的电脑服务器.
 */
public final class MCPManager {

    private static final Logger logger = LoggerFactory.getLogger(MCPManager.class);

    private final Map<String, Set<String>> roleTools = new LinkedHashMap<>();

    /** 加载 MCP 分组规则 JSON (classpath: /mcp_group_rules.json). */
    public static Map<String, Object> loadRules() {
        try (InputStream in = MCPManager.class.getResourceAsStream("/mcp_group_rules.json")) {
            if (in == null) {
                throw new IllegalStateException("MCP 分组规则文件不存在: mcp_group_rules.json");
            }
            return Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取 MCP 分组规则失败", e);
        }
    }

    /** 判断工具名是否匹配分组规则 (支持通配符 *). */
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
     * 把指定组的 MCP 工具作为默认工具安装到角色 (从角色电脑的独立服务器).
     * 返回成功安装的工具名列表.
     */
    public List<String> installGroupDefaults(AgentRole role, String group) {
        // 1) 确保角色电脑的独立 MCP 服务器已安装
        Computer computer = role.computer();
        computer.installMcpServer();
        List<String> installed = computer.listInstalledMcpTools();
        if (installed.isEmpty()) {
            logger.warn("[{}] 电脑无 MCP 服务器工具, 跳过默认组 '{}'", role.roleId, group);
            return new ArrayList<>();
        }
        // 2) 按分组规则过滤
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
        // 3) 逐个安装到角色
        List<String> ok = new ArrayList<>();
        for (String name : targets) {
            try {
                String r = addTool(role, name);
                if (!r.startsWith("错误")) {
                    ok.add(name);
                }
            } catch (Exception e) {
                logger.error("[{}] MCP 默认工具安装失败: {}", role.roleId, name, e);
            }
        }
        logger.info("[{}] MCP 默认工具组 '{}' 已安装 {} 个工具: {}",
                role.roleId, group, ok.size(), ok);
        return ok;
    }

    /** 为角色安装一个 MCP 工具 (来自该角色电脑的独立 MCP 服务器). */
    public String addTool(AgentRole role, String toolName) {
        Computer computer = role.computer();
        computer.installMcpServer();
        String roleId = role.roleId;
        Set<String> mine = roleTools.computeIfAbsent(roleId, k -> new LinkedHashSet<>());
        if (mine.contains(toolName)) {
            return "工具 '" + toolName + "' 已添加给 " + roleId + ", 无需重复添加.";
        }
        ToolDef td = computer.getMcpTool(toolName);
        if (td == null) {
            return "错误: 电脑[" + roleId + "] 的 MCP 服务器上没有名为 '" + toolName + "' 的工具. "
                    + "本电脑已安装: " + (computer.listInstalledMcpTools().isEmpty()
                    ? "(无)" : computer.listInstalledMcpTools())
                    + ". 可用 mcp_search / mcp_list 查看全局可用工具.";
        }
        // 角色注册代理 handler → 转发到电脑服务器上执行
        Computer compRef = computer;
        String tname = toolName;
        role.addSingleTool(td.name, td.description, td.inputSchema,
                args -> compRef.runMcpTool(tname, args), td.source);
        mine.add(toolName);
        logger.info("[{}] MCP 工具已安装到电脑: {} (来源 {})", roleId, toolName, td.source);
        return "成功: 工具 '" + toolName + "' 已安装到 " + roleId
                + " (" + truncate(td.description, 60) + ")";
    }

    /** 从角色电脑卸载一个 MCP 工具. */
    public String removeTool(AgentRole role, String toolName) {
        String roleId = role.roleId;
        Set<String> mine = roleTools.getOrDefault(roleId, new LinkedHashSet<>());
        if (!mine.contains(toolName)) {
            return "工具 '" + toolName + "' 尚未添加给 " + roleId + ", 无需移除.";
        }
        Computer computer = role.computer();
        computer.uninstallMcpTool(toolName);
        role.removeSingleTool(toolName);
        mine.remove(toolName);
        logger.info("[{}] MCP 工具已从电脑卸载: {}", roleId, toolName);
        return "成功: 工具 '" + toolName + "' 已从 " + roleId + " 的电脑卸载.";
    }

    /** 列出角色电脑上已安装的 MCP 工具. */
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
