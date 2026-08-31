package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.computers.Computer;
import com.maf.scheduler.utils.Json;
import com.maf.scheduler.core.ToolRegistry.ToolDef;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.core.ToolRegistry.ToolHandler;
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
 * MCP 工具管理类 (MCPManager) — Python 版 mcp_manager.py + mcp_toolkit.py 的
 * 分组规则部分.
 *
 * 架构 (C 方案): 每个角色的电脑 (podman 容器) 内运行独立的 MCP filesystem
 * 服务器, 授权目录 = 容器内工作目录. MCPManager 不维护全局工具池, 所有查询
 * /安装都基于当前角色自己的电脑服务器.
 *
 * 同时把管理操作打包成 LLM tool-call 工具 (mcp_manager 工具类):
 * mcp_search / mcp_list / mcp_add / mcp_remove / mcp_my_tools.
 */
public final class MCPManagerToolkit {

    private static final Logger logger = LoggerFactory.getLogger(MCPManagerToolkit.class);

    private MCPManagerToolkit() {
    }

    /** MCP 工具管理器: 管理每角色电脑上的 MCP 服务器工具. */
    public static final class MCPManager {
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
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** 把 MCP 管理操作打包成 LLM 可调用的工具类. */
    public static ToolKit createMcpManagerToolkit(MCPManagerToolkit.MCPManager manager) {
        ToolKit tk = new ToolKit("mcp_manager", "MCP 工具管理: 搜索/添加/移除本地 MCP 工具");
        tk.bind("manager", manager);

        ToolHandler mcpSearch = args -> {
            String kw = Json.str(args, "keyword", "").strip().toLowerCase();
            if (kw.isEmpty()) {
                return "请提供 keyword 搜索词.";
            }
            Computer computer = role(tk).computer();
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
                return "没有匹配 '" + kw + "' 的 MCP 工具. 本电脑服务器已装: "
                        + (computer.listInstalledMcpTools().isEmpty() ? "(无)"
                        : computer.listInstalledMcpTools()) + ". 可用 mcp_list 查看全部.";
            }
            List<String> out = new ArrayList<>();
            out.add("搜索 '" + kw + "' 找到 " + hits + " 个工具:");
            out.addAll(lines);
            return String.join("\n", out);
        };

        ToolHandler mcpList = args -> {
            Computer computer = role(tk).computer();
            List<String> avail = new ArrayList<>();
            for (ToolDef td : computer.iterMcpTools()) {
                avail.add("- " + td.name + ": " + td.description);
            }
            if (avail.isEmpty()) {
                return "本电脑暂无 MCP 服务器工具 (服务器可能未连接).";
            }
            List<String> out = new ArrayList<>();
            out.add("本电脑 MCP 服务器共有 " + avail.size() + " 个工具:");
            out.addAll(avail);
            return String.join("\n", out);
        };

        ToolHandler mcpAdd = args -> {
            String name = Json.str(args, "tool_name", "").strip();
            if (name.isEmpty()) {
                return "请提供 tool_name.";
            }
            MCPManagerToolkit.MCPManager mgr = (MCPManagerToolkit.MCPManager) tk.require("manager", "MCP 管理器");
            return mgr.addTool(role(tk), name);
        };

        ToolHandler mcpRemove = args -> {
            String name = Json.str(args, "tool_name", "").strip();
            if (name.isEmpty()) {
                return "请提供 tool_name.";
            }
            MCPManagerToolkit.MCPManager mgr = (MCPManagerToolkit.MCPManager) tk.require("manager", "MCP 管理器");
            return mgr.removeTool(role(tk), name);
        };

        ToolHandler mcpMyTools = args -> {
            MCPManagerToolkit.MCPManager mgr = (MCPManagerToolkit.MCPManager) tk.require("manager", "MCP 管理器");
            List<Map<String, String>> mine = mgr.listRoleTools(role(tk));
            if (mine.isEmpty()) {
                return "你还没有添加任何 MCP 工具. 可用 mcp_search / mcp_list 寻找, 用 mcp_add 添加.";
            }
            List<String> lines = new ArrayList<>();
            lines.add("你已添加 " + mine.size() + " 个 MCP 工具:");
            for (Map<String, String> m : mine) {
                lines.add("- " + m.get("name") + ": " + m.get("description"));
            }
            return String.join("\n", lines);
        };

        Map<String, Object> keywordSchema = new LinkedHashMap<>();
        keywordSchema.put("type", "object");
        keywordSchema.put("properties", Map.of(
                "keyword", TalkToolkit.mapOf("string", "搜索关键词, 如 file/git/issue/read")));
        keywordSchema.put("required", List.of("keyword"));

        Map<String, Object> nameSchema = new LinkedHashMap<>();
        nameSchema.put("type", "object");
        nameSchema.put("properties", Map.of(
                "tool_name", TalkToolkit.mapOf("string", "要添加的工具名, 如 read_file")));
        nameSchema.put("required", List.of("tool_name"));

        tk.addPythonTool("mcp_search",
                "搜索本地已有的 MCP 工具 (按名称或描述关键词). 先搜索找到合适的工具, 再用 mcp_add 添加给自己.",
                keywordSchema, mcpSearch);
        tk.addPythonTool("mcp_list",
                "列出本地全部可用的 MCP 工具 (名称+简述). 查看有哪些工具可用.",
                TalkToolkit.emptySchema(), mcpList);
        tk.addPythonTool("mcp_add",
                "为当前角色添加一个本地已有的 MCP 工具. 添加后即可在后续任务中直接调用该工具.",
                nameSchema, mcpAdd);
        tk.addPythonTool("mcp_remove",
                "从当前角色移除一个已添加的 MCP 工具.",
                nameSchema, mcpRemove);
        tk.addPythonTool("mcp_my_tools",
                "查看当前角色已添加的 MCP 工具列表.",
                TalkToolkit.emptySchema(), mcpMyTools);
        return tk;
    }

    private static AgentRole role(ToolKit tk) {
        return (AgentRole) tk.require("role", "角色");
    }

    /** 将当前角色绑定到 mcp_manager 工具类. */
    public static void bindMcpManagerToToolkit(ToolKit toolkit, AgentRole role) {
        toolkit.bind("role", role);
    }
}
