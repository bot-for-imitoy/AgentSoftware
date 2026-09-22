package com.agent.software.computers;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;
import com.agent.software.tools.toolkits.mcp.MCPManager;
import com.agent.software.utils.Data;
import com.agent.software.utils.UUIDObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 个人电脑抽象：命令 + 文件操作 + 容器内的 MCP 工具。
 *
 * <p>按 D11 只保留安装/卸载/启动/停止；路径细节（hostDir/workdir/driveRoot）不再对外暴露。
 */
public abstract class Computer extends UUIDObject implements Data {

    private static final Logger logger = LoggerFactory.getLogger(Computer.class);

    private static final String COMPUTERS_ROOT = "./data/computers";
    private static final String DRIVE_ROOT = "./data/drive";
    private static final String DEFAULT_IMAGE = "agentsoftware-base:latest";
    private static final String CONTAINERFILE = "Containerfile";

    protected boolean ison = false;
    private final Role role;
    private MCPServer mcpServer;
    private final Set<String> installedMcpTools = new LinkedHashSet<>();

    protected Computer(Role role) {
        super();
        this.role = role;
    }

    protected Computer(Role role, String uuid) {
        super(uuid);
        this.role = role;
    }

    public Role getRole() {
        return role;
    }

    public boolean isOn() {
        return ison;
    }

    public abstract void powerOn();

    public abstract void powerOff();

    public void reboot() {
        powerOff();
        powerOn();
    }

    /** 销毁是电脑自己的事；管理类只负责把它移出注册表。 */
    public abstract void destroy();

    // ── 命令 / 文件 ──────────────────────────────────────────────

    public abstract String runCommand(String command, int timeout, int maxChars);

    public abstract String readFile(String path);

    public abstract void writeFile(String path, String content);

    public abstract String listDir(String path);

    public abstract void deleteFile(String path);

    // ── MCP ──────────────────────────────────────────────────────

    public void installMcpTool(String group, String tool) {
        Map<String, Object> rules = MCPManager.loadRules();
        Object packages = rules.get("groups");
        if (packages instanceof Map<?, ?> groups && groups.get(group) instanceof Map<?, ?> g) {
            Object pkg = g.get("package");
            if (pkg != null) {
                ensureServer(String.valueOf(pkg), List.of());
            }
        }
        if (tool != null && !tool.isBlank()) {
            installedMcpTools.add(tool);
        }
    }

    public void uninstallMcpTool(String toolName) {
        installedMcpTools.remove(toolName);
    }

    public void startMcpServers() {
        if (mcpServer == null) {
            return;
        }
        try {
            mcpServer.connect();
        } catch (Exception e) {
            logger.warn("Computer[{}] failed to start MCP server", roleId(), e);
        }
    }

    public void stopMcpServers() {
        if (mcpServer != null) {
            mcpServer.close();
        }
    }

    /** 把 MCP 工具包装成普通 Tool，供 Role 的工具循环调用。 */
    public List<Tool> getMcpTools() {
        List<Tool> out = new ArrayList<>();
        if (mcpServer == null) {
            return out;
        }
        for (Map<String, Object> spec : mcpServer.listTools()) {
            String name = String.valueOf(spec.get("name"));
            String description = spec.get("description") == null ? "" : String.valueOf(spec.get("description"));
            out.add(new Tool() {
                @Override
                public String getToolName() {
                    return name;
                }

                @Override
                public Map<String, Object> getSchema() {
                    return new LinkedHashMap<>();
                }

                @Override
                public String getDescription() {
                    return description;
                }

                @Override
                public String handler(Map<String, Object> args) {
                    return runMcpTool(name, args);
                }
            });
        }
        return out;
    }

    public String runMcpTool(String name, Map<String, Object> args) {
        if (mcpServer == null) {
            return "mcp error: no MCP server on this computer";
        }
        try {
            return mcpServer.callTool(name, args == null ? Map.of() : args);
        } catch (Exception e) {
            return "mcp error: " + e.getMessage();
        }
    }

    private void ensureServer(String packageName, List<String> args) {
        if (mcpServer != null) {
            return;
        }
        mcpServer = new MCPServer(packageName, args);
    }

    // ── 内部路径（不再对外暴露）──────────────────────────────────

    protected String roleId() {
        return role == null ? "shared" : role.roleId;
    }

    protected Path hostDir() {
        String root = System.getenv().getOrDefault("AGENTSOFTWARE_DATA_DIR", "data");
        return Paths.get(root, "computers", roleId()).toAbsolutePath();
    }

    protected Path driveDir() {
        String root = System.getenv().getOrDefault("AGENTSOFTWARE_DATA_DIR", "data");
        return Paths.get(root, "drive", roleId()).toAbsolutePath();
    }

    // ── 持久化 ──────────────────────────────────────────────────

    @Override
    public Map<String, String> getData() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("uuid", uuid);
        d.put("role_id", roleId());
        d.put("ison", Boolean.toString(ison));
        d.put("class", getClass().getName());
        return d;
    }

    @Override
    public void loadData(Map<String, String> data) {
        if (data == null) {
            return;
        }
        if (data.containsKey("uuid")) {
            this.uuid = data.get("uuid");
        }
        this.ison = Boolean.parseBoolean(data.getOrDefault("ison", "false"));
        // role 绑定属于运行时状态，由构造方提供；这里不重建。
    }

    protected static String defaultImage() {
        return System.getenv().getOrDefault("AGENTSOFTWARE_BASE_IMAGE", DEFAULT_IMAGE);
    }

    protected static String containerfile() {
        return System.getenv().getOrDefault("AGENTSOFTWARE_CONTAINERFILE", CONTAINERFILE);
    }
}
