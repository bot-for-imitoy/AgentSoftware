package com.agent.software.tool.mcp;

import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.RoleId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.agent.software.tool.mcp.McpBridge.McpToolInfo;

/**
 * stdio JSON-RPC 形态的 MCP 工具桥：按角色安装/卸载工具并转发调用。
 *
 * <p><b>吸收范围</b>：master 的 {@code MCPManager}（可安装目录 + 每角色安装集合）收进本类；
 * 可安装目录来自 classpath 资源 {@code /mcp_group_rules.json}（{@code servers} + 各 group 的
 * {@code match} 规则），解析失败只 warn 并退化为空目录。
 *
 * <p><b>持久化</b>：每角色的安装集合落盘到 {@code AppPaths.dataFile("mcp", <roleId>.json)}，
 * 采用"先写 .tmp 再 move"的原子写；角色名做过路径字符清洗，避免 roleId 里的分隔符逃出 mcp 目录。
 * 内存缓存 + {@code ConcurrentHashMap} 的按 key 串行保证线程安全。
 *
 * <p><b>与 master 的契约缺口</b>：master {@code MCPManager.addTool()} 会先
 * {@code computer.ensureMcpServers()} 拿到电脑上真实存在的 MCP server 工具（{@code ToolDef}），
 * 再 {@code role.addSingleTool(...)} 把代理 handler 挂到该角色工具表上；{@code removeTool()}
 * 同时调用 {@code computer.uninstallMcpTool()} 与 {@code role.removeSingleTool()}。
 * {@link McpBridge} 既没有 {@code Shell} 也没有工具表，因此本实现<b>只做"安装状态 + 记录"</b>：
 * 校验不了工具是否真在电脑上存在，也不注册可执行 handler。真正把 MCP 工具挂到电脑上、
 * 并把 stdio JSON-RPC（master {@code core.MCPServer} 的 {@code callTool}）接进来，
 * 由 {@code McpToolkit} 或后续扩展负责——当前端口只覆盖 search/install/uninstall 三件事。
 */
public final class StdioMcpBridge implements McpBridge {

    private static final Logger logger = LoggerFactory.getLogger(StdioMcpBridge.class);

    /** 可安装 MCP 工具目录（classpath 资源）。 */
    private static final String RULES_RESOURCE = "/mcp_group_rules.json";

    /** 目录里查不到描述时的兜底文案。 */
    private static final String UNKNOWN_DESCRIPTION = "（未收录于内置 MCP 清单，按名称记录）";

    private final AppPaths paths;
    private final JacksonJsonCodec codec = new JacksonJsonCodec();

    /** roleId → 已安装工具（name → description）。 */
    private final ConcurrentHashMap<String, Map<String, String>> installed = new ConcurrentHashMap<>();

    /** 懒加载的可安装目录（name → description），只读共享。 */
    private volatile Map<String, String> catalog;

    /** 绑定数据路径（MCP 安装状态与日志落盘位置）。 */
    public StdioMcpBridge(AppPaths paths) {
        this.paths = paths;
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    @Override
    public List<McpToolInfo> search(String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<McpToolInfo> hits = new ArrayList<>();
        for (Map.Entry<String, String> entry : catalog().entrySet()) {
            String name = entry.getKey();
            String description = entry.getValue();
            if (kw.isEmpty()
                    || name.toLowerCase(Locale.ROOT).contains(kw)
                    || description.toLowerCase(Locale.ROOT).contains(kw)) {
                hits.add(new McpToolInfo(name, description));
            }
        }
        return hits;
    }

    @Override
    public List<McpToolInfo> installed(RoleId agent) {
        requireAgent(agent);
        Map<String, String> mine = installed.computeIfAbsent(agent.value(), key -> load(agent));
        List<String> names = new ArrayList<>(mine.keySet());
        Collections.sort(names);
        List<McpToolInfo> out = new ArrayList<>(names.size());
        for (String name : names) {
            out.add(new McpToolInfo(name, mine.get(name)));
        }
        return out;
    }

    // ── 安装 / 卸载 ─────────────────────────────────────────────────────────

    @Override
    public void install(RoleId agent, String toolName) {
        requireAgent(agent);
        String name = toolName == null ? "" : toolName.trim();
        if (name.isEmpty()) {
            throw new DomainError("mcp.tool.blank", "MCP 工具名不能为空");
        }
        installed.compute(agent.value(), (key, existing) -> {
            Map<String, String> mine = existing == null ? load(agent) : new LinkedHashMap<>(existing);
            if (mine.containsKey(name)) {
                return mine; // 幂等
            }
            mine.put(name, catalog().getOrDefault(name, UNKNOWN_DESCRIPTION));
            persist(agent, mine);
            return mine;
        });
        logger.info("角色 [{}] 安装 MCP 工具: {}", agent.value(), name);
    }

    @Override
    public void uninstall(RoleId agent, String toolName) {
        requireAgent(agent);
        String name = toolName == null ? "" : toolName.trim();
        if (name.isEmpty()) {
            throw new DomainError("mcp.tool.blank", "MCP 工具名不能为空");
        }
        installed.compute(agent.value(), (key, existing) -> {
            Map<String, String> mine = existing == null ? load(agent) : new LinkedHashMap<>(existing);
            if (mine.remove(name) == null) {
                return mine; // 没装过，nothing to remove
            }
            persist(agent, mine);
            return mine;
        });
        logger.info("角色 [{}] 卸载 MCP 工具: {}", agent.value(), name);
    }

    // ── 可安装目录 ──────────────────────────────────────────────────────────

    /** 懒加载目录（双重检查）；资源缺失 / 解析失败返回空目录并 warn，不退化成异常。 */
    private Map<String, String> catalog() {
        Map<String, String> local = catalog;
        if (local == null) {
            synchronized (this) {
                local = catalog;
                if (local == null) {
                    local = loadCatalog();
                    catalog = local;
                }
            }
        }
        return local;
    }

    private Map<String, String> loadCatalog() {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = StdioMcpBridge.class.getResourceAsStream(RULES_RESOURCE)) {
            if (in == null) {
                logger.warn("classpath 资源 {} 不存在，MCP 可安装目录为空", RULES_RESOURCE);
                return out;
            }
            Map<String, Object> rules = codec.readMap(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            Object servers = rules.get("servers");
            if (servers instanceof List<?> list) {
                for (Object server : list) {
                    if (server == null) {
                        continue;
                    }
                    String name = String.valueOf(server).trim();
                    if (!name.isEmpty()) {
                        out.putIfAbsent(name, "MCP 服务器包（可安装）");
                    }
                }
            }
            Object groups = rules.get("groups");
            if (groups instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> group)) {
                        continue;
                    }
                    String description = group.get("description") == null
                            ? "" : String.valueOf(group.get("description")).trim();
                    Object match = group.get("match");
                    if (!(match instanceof List<?> patterns)) {
                        continue;
                    }
                    for (Object pattern : patterns) {
                        if (pattern == null) {
                            continue;
                        }
                        String name = String.valueOf(pattern).trim();
                        if (!name.isEmpty()) {
                            out.putIfAbsent(name, description);
                        }
                    }
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            logger.warn("读取 classpath 资源 {} 失败，MCP 可安装目录为空: {}", RULES_RESOURCE, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    // ── 落盘 ────────────────────────────────────────────────────────────────

    /** 每角色安装状态文件：{@code dataDir/mcp/<清洗后的 roleId>.json}。 */
    private Path stateFile(RoleId agent) {
        if (paths == null) {
            throw new DomainError("mcp.paths.missing", "StdioMcpBridge 未绑定 AppPaths，无法定位安装状态文件");
        }
        return paths.dataFile("mcp", safeName(agent.value()) + ".json");
    }

    /** 角色名清洗：只保留 [A-Za-z0-9._-]，防止 roleId 里的路径分隔符逃出 mcp 目录。 */
    private static String safeName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            sb.append(keep ? c : '_');
        }
        return sb.toString();
    }

    private Map<String, String> load(RoleId agent) {
        Path file = stateFile(agent);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> root = codec.readMap(Files.readString(file, StandardCharsets.UTF_8));
            return parseTools(root.get("tools"));
        } catch (IOException | RuntimeException e) {
            logger.warn("读取 MCP 安装状态失败 {}: {}", file, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** 容错解析：{tools: {name: description}} 与 {tools: [{name, description}]} 两种历史形态都接受。 */
    private static Map<String, String> parseTools(Object tools) {
        Map<String, String> out = new LinkedHashMap<>();
        if (tools instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(String.valueOf(entry.getKey()),
                            entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                }
            }
        } else if (tools instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && map.get("name") != null) {
                    out.put(String.valueOf(map.get("name")),
                            map.get("description") == null ? "" : String.valueOf(map.get("description")));
                } else if (item instanceof String name && !name.isBlank()) {
                    out.put(name, "");
                }
            }
        }
        return out;
    }

    /** 原子写：先写同名 .tmp，再 move 覆盖正式文件（不支持 ATOMIC_MOVE 时退回普通 move）。 */
    private void persist(RoleId agent, Map<String, String> tools) {
        Path file = stateFile(agent);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("agent", agent.value());
            root.put("updated_at", java.time.Instant.now().toString());
            root.put("tools", new LinkedHashMap<>(tools));
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, codec.writePretty(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new DomainError("mcp.persist.failed", "写入 MCP 安装状态失败: " + file, e);
        }
    }

    private static void requireAgent(RoleId agent) {
        if (agent == null || agent.value().isBlank()) {
            throw new DomainError("mcp.agent.missing", "MCP 操作需要调用者角色");
        }
    }
}
