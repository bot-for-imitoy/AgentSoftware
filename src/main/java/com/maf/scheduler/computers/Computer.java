package com.maf.scheduler.computers;

import com.maf.scheduler.core.MCPServer;
import com.maf.scheduler.core.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 电脑基类与实现 (Computer) — 每个角色一台个人电脑 (Python 版 computer.py).
 *
 * 三种实现:
 *   - {@link LocalComputer}: 本地目录模拟 (data/computers/&lt;role_id&gt;/).
 *   - {@link PodmanComputer}: podman 容器虚拟电脑 (默认; 容器名 maf-&lt;role_id&gt;).
 *   - {@link SSHComputer}: 通过 ssh 连接远程主机执行命令.
 */
public abstract class Computer {

    private static final Logger logger = LoggerFactory.getLogger(Computer.class);

    // ── 路径常量 (使用处统一引用) ──────────────────────────────
    public static final String COMPUTERS_ROOT = "./data/computers";
    public static final String DRIVE_ROOT = "./data/drive";

    // 电脑默认容器镜像: 由项目根 Containerfile 定义
    public static final String DEFAULT_IMAGE = "maf-base:latest";
    public static final String CONTAINERFILE = "Containerfile";

    public final String roleId;
    protected boolean on = false;
    protected final boolean autoMcp;              // 自动创建的电脑: 创建时自动安装 MCP 服务器
    protected final Map<String, ToolRegistry.ToolDef> mcpTools = new LinkedHashMap<>();
    protected MCPServer mcpServer = null;          // 本电脑独立的 MCP 服务器连接 (懒创建)
    protected String connectError = null;          // 最近一次 MCP 连接失败原因 (诊断用)

    protected Computer(String roleId, boolean autoMcp) {
        this.roleId = roleId;
        this.autoMcp = autoMcp;
    }

    // ── MCP 会话存活检测与重连 ───────────────────────────

    protected boolean mcpServerAlive() {
        MCPServer srv = mcpServer;
        if (srv == null) {
            return true;
        }
        try {
            return srv.isAlive(5.0);
        } catch (Exception e) {
            return false;
        }
    }

    /** MCP 服务器会话失效时重建 (跨天关机后 podman stop 杀死 stdio 管道). */
    protected void reconnectMcpServer() {
        if (mcpServer == null || mcpServerAlive()) {
            return;
        }
        logger.warn("电脑[{}] MCP 服务器会话已失效, 正在重建...", roleId);
        try {
            mcpServer.close();
        } catch (Exception ignored) {
        }
        mcpServer = null;
        mcpTools.clear();  // 旧 handler 绑定的是已死服务器
        try {
            installMcpServer();
        } catch (Exception e) {
            logger.error("电脑[{}] MCP 服务器重建失败", roleId, e);
        }
    }

    // ── 抽象接口 (子类实现) ──────────────────────────────

    public abstract String powerOn();

    public abstract String powerOff();

    /** 运行命令 (在个人电脑上执行). 返回命令输出. */
    public abstract String runCommand(String command, int timeout, int maxChars);

    public abstract String readFile(String path);

    /** 写入个人电脑上的文件 (自动创建父目录). 返回路径. */
    public abstract String writeFile(String path, String content);

    /** 列出个人电脑指定目录内容 (默认工作目录). */
    public abstract String listDir(String path);

    /** 删除个人电脑上的文件. 返回状态说明. */
    public abstract String deleteFile(String path);

    /** 统一格式化命令执行结果 (exit 码 + 输出截断). */
    protected String formatResult(ProcessResult r, int maxChars) {
        String output = (r.stdout == null ? "" : r.stdout) + (r.stderr == null ? "" : r.stderr);
        output = output.strip();
        if (r.returnCode != 0) {
            return "[exit " + r.returnCode + "] " + truncate(output, maxChars);
        }
        return truncate(output, maxChars).isEmpty() ? "(无输出)" : truncate(output, maxChars);
    }

    protected static String truncate(String s, int n) {
        if (s == null || s.length() <= n) {
            return s == null ? "" : s;
        }
        return s.substring(0, n);
    }

    /** 进程执行结果 (stdout/stderr/returncode). */
    protected static final class ProcessResult {
        public String stdout = "";
        public String stderr = "";
        public int returnCode = 0;

        ProcessResult(String stdout, String stderr, int returnCode) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.returnCode = returnCode;
        }
    }

    /** 执行进程, 返回 (stdout+stderr, returncode). */
    protected static ProcessResult runProcess(List<String> cmd, String stdinInput,
                                              int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process p = pb.start();

            StringBuilder stdoutBuffer = new StringBuilder();
            StringBuilder stderrBuffer = new StringBuilder();

            // 1. 异步实时读取 stdout
            Thread stdoutThread = Thread.ofVirtual().start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println(line); // 即时控制台输出
                        stdoutBuffer.append(line).append("\n"); // 累加日志
                    }
                } catch (IOException ignored) {}
            });

            // 2. 异步实时读取 stderr
            Thread stderrThread = Thread.ofVirtual().start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.err.println(line); // 即时标准错误输出
                        stderrBuffer.append(line).append("\n");
                    }
                } catch (IOException ignored) {}
            });

            // 3. 写入 stdin
            if (stdinInput != null) {
                try (var w = p.outputWriter(StandardCharsets.UTF_8)) {
                    w.write(stdinInput);
                }
            }

            // 4. 等待子进程完成并确保读取线程结束
            boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            int rc;
            if (!done) {
                p.destroyForcibly();
                rc = -1;
            } else {
                rc = p.exitValue();
            }

            // 5. 等待读取线程结束 (有界 join). 子进程退出后管道通常会快速 EOF;
            //    但 podman 的子进程后代 (conmon / 容器进程) 可能仍握着管道写端,
            //    使 readLine 永久阻塞 — 无超时 join 会让 runProcess 自身卡死
            //    (叠加 pod() 曾有的 600000s 超时 = 控制台静默近 7 天).
            long joinDeadline = System.currentTimeMillis() + 30_000;
            stdoutThread.join(Math.max(1, joinDeadline - System.currentTimeMillis()));
            stderrThread.join(Math.max(1, joinDeadline - System.currentTimeMillis()));

            return new ProcessResult(stdoutBuffer.toString(), stderrBuffer.toString(), rc);
        } catch (IOException e) {
            return new ProcessResult("", "错误: 进程启动失败 - " + e.getMessage(), -2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessResult("", "错误: 进程被中断", -3);
        }
    }

    public boolean isAutoMcp() {
        return autoMcp;
    }

    // ── MCP 工具安装与执行 (所有实现共用) ────────────────

    /** 宿主机上该电脑工作目录的映射路径 (MCP 服务器授权目录). */
    public String hostDir() {
        return "";
    }

    /**
     * 在本电脑上安装独立的 MCP 服务器 (filesystem, 授权本电脑目录).
     * 幂等: 已安装则直接返回. 返回已安装的工具名列表.
     */
    public List<String> installMcpServer() {
        if (mcpServer != null) {
            return listInstalledMcpTools();
        }
        if (!autoMcp) {
            logger.info("电脑[{}] 非自动创建, 不自动安装 MCP 服务器", roleId);
            return new ArrayList<>();
        }
        if (hostDir().isEmpty()) {
            logger.warn("电脑[{}] 无宿主机目录映射, 跳过 MCP 服务器安装 (SSH 远程电脑)", roleId);
            return new ArrayList<>();
        }
        try {
            mcpServer = new MCPServer("@modelcontextprotocol/server-filesystem",
                    List.of(hostDir()));
            mcpServer.connect();
            if (!mcpServer.isAlive(5.0)) {
                throw new RuntimeException("MCP 服务器连接失败: "
                        + (mcpServer.connectError() != null ? mcpServer.connectError() : "未知"));
            }
            for (Map<String, Object> tool : mcpServer.listTools()) {
                String tname = String.valueOf(tool.get("name"));
                if (tname == null || tname.isEmpty() || "null".equals(tname)) {
                    continue;
                }
                MCPServer server = mcpServer;
                ToolRegistry.ToolDef td = new ToolRegistry.ToolDef(
                        tname,
                        String.valueOf(tool.getOrDefault("description", "")),
                        mapOf(tool.get("inputSchema")),
                        args -> server.callTool(tname, args),
                        "mcp:" + server.packageName + " (本电脑)");
                mcpTools.put(tname, td);
            }
            logger.info("电脑[{}] 独立 MCP 服务器已安装, {} 个工具: {}",
                    roleId, mcpTools.size(), listInstalledMcpTools());
        } catch (Exception exc) {
            connectError = String.valueOf(exc.getMessage());
            logger.error("电脑[{}] MCP 服务器安装失败", roleId, exc);
            return new ArrayList<>();
        }
        return listInstalledMcpTools();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        return new LinkedHashMap<>();
    }

    /** 从本电脑卸载一个 MCP 工具. 返回是否卸载成功. */
    public boolean uninstallMcpTool(String toolName) {
        return mcpTools.remove(toolName) != null;
    }

    /** 列出本电脑已安装的 MCP 工具名 (排序). */
    public List<String> listInstalledMcpTools() {
        List<String> names = new ArrayList<>(mcpTools.keySet());
        names.sort(String::compareTo);
        return names;
    }

    public ToolRegistry.ToolDef getMcpTool(String name) {
        return mcpTools.get(name);
    }

    public List<ToolRegistry.ToolDef> iterMcpTools() {
        return new ArrayList<>(mcpTools.values());
    }

    /** 运行 MCP 工具 (在本电脑上执行). */
    public String runMcpTool(String toolName, Map<String, Object> args) {
        ToolRegistry.ToolDef td = mcpTools.get(toolName);
        if (td == null) {
            return "错误: MCP 工具 '" + toolName + "' 未安装到本电脑. 已安装: "
                    + (listInstalledMcpTools().isEmpty() ? "(无)" : listInstalledMcpTools())
                    + ". 可用 mcp_search / mcp_list 查看可用工具, 用 mcp_add 安装.";
        }
        if (td.handler == null) {
            return "错误: 工具 '" + toolName + "' 缺少可执行 handler.";
        }
        try {
            return String.valueOf(td.handler.handle(args));
        } catch (Exception exc) {
            logger.error("MCP 工具 {} 执行失败", toolName, exc);
            return "错误: 工具 '" + toolName + "' 执行失败 - " + exc.getMessage();
        }
    }

    // ── 通用 ──────────────────────────────────────────────

    public boolean isOn() {
        return on;
    }

    /** 重启电脑 (关机后再开机). 所有实现通用. */
    public String reboot() {
        String off = powerOff();
        String on = powerOn();
        return "电脑[" + roleId + "] 已重启.\n- " + off + "\n- " + on;
    }

    /** 个人工作目录 (电脑上的路径). 子类可覆盖. */
    public String workdir() {
        return "/home/agent";
    }

    /** 企业云盘挂载根路径. */
    public String driveRoot() {
        return "/mnt/drive";
    }

    /** 电脑状态描述 (供 LLM 查看). */
    public String describe() {
        return "电脑[" + roleId + "] (" + getClass().getSimpleName() + "): "
                + "状态=" + (on ? "开机" : "关机") + ", 工作目录=" + workdir();
    }

    // ── LocalComputer (本地目录模拟) ──────────────────────────

    /** 本地目录模拟电脑 (开发/降级用). */
    public static final class LocalComputer extends Computer {
        public String name = "";
        public String username = "agent";
        public int uid = 1100;
        private final Path dir;
        private final String driveRoot;

        public LocalComputer(String roleId, boolean autoMcp, String baseDir, String driveDir,
                             String name, String username, int uid) {
            super(roleId, autoMcp);
            this.name = name != null ? name : "";
            this.username = (username == null || username.isEmpty()) ? "agent" : username;
            this.uid = uid <= 0 ? 1100 : uid;
            this.dir = Paths.get(baseDir != null ? baseDir : COMPUTERS_ROOT).toAbsolutePath()
                    .resolve(roleId == null || roleId.isEmpty() ? "shared" : roleId);
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new RuntimeException("创建本地电脑目录失败: " + dir, e);
            }
            this.driveRoot = Paths.get(driveDir != null ? driveDir : DRIVE_ROOT).toAbsolutePath().toString();
            this.on = true;  // 本地模拟默认开机
        }

        @Override
        public String hostDir() {
            return dir.toString();
        }

        @Override
        public String workdir() {
            return dir.toString();
        }

        @Override
        public String driveRoot() {
            return driveRoot;
        }

        @Override
        public String powerOn() {
            on = true;
            try {
                Files.createDirectories(dir);
            } catch (IOException ignored) {
            }
            return "电脑[" + roleId + "] (本地模拟) 已开机. 工作目录: " + dir;
        }

        @Override
        public String powerOff() {
            on = false;
            return "电脑[" + roleId + "] (本地模拟) 已关机.";
        }

        private Path resolve(String path) {
            Path p = Paths.get(path);
            if (!p.isAbsolute()) {
                p = dir.resolve(p);
            }
            return p;
        }

        @Override
        public String runCommand(String command, int timeout, int maxChars) {
            if (!on) {
                return "错误: 电脑未开机.";
            }
            ProcessResult r = runProcess(List.of("sh", "-c", command), null, timeout);
            return formatResult(r, maxChars);
        }

        @Override
        public String readFile(String path) {
            Path p = resolve(path);
            if (!Files.exists(p)) {
                return "文件不存在: " + p;
            }
            try {
                return Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "错误: 读取失败 - " + e.getMessage();
            }
        }

        @Override
        public String writeFile(String path, String content) {
            Path p = resolve(path);
            try {
                Files.createDirectories(p.getParent());
                Files.writeString(p, content, StandardCharsets.UTF_8);
                return p.toString();
            } catch (IOException e) {
                return "错误: 写入失败 - " + e.getMessage();
            }
        }

        @Override
        public String listDir(String path) {
            Path p = resolve(path == null || path.isEmpty() ? "" : path);
            if (!Files.exists(p) || !Files.isDirectory(p)) {
                return "目录不存在: " + p;
            }
            try (var stream = Files.list(p)) {
                List<String> names = new ArrayList<>();
                stream.forEach(f -> names.add(f.getFileName().toString()));
                names.sort(String::compareTo);
                return names.isEmpty() ? "(空目录)" : String.join("\n", names);
            } catch (IOException e) {
                return "错误: 列出目录失败 - " + e.getMessage();
            }
        }

        @Override
        public String deleteFile(String path) {
            Path p = resolve(path);
            if (!Files.exists(p)) {
                return "文件不存在: " + p;
            }
            try {
                Files.delete(p);
                return "已删除: " + p;
            } catch (IOException e) {
                return "错误: 删除失败 - " + e.getMessage();
            }
        }
    }
}
