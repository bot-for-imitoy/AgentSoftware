package com.maf.scheduler.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Podman 容器虚拟电脑 (默认实现) — Python 版 PodmanComputer.
 *
 * 每个角色一个容器 (名 maf-&lt;role_id&gt;), 命令经 podman exec 执行.
 * 需要本机安装 podman; 未安装时构造直接抛 RuntimeException.
 */
public class PodmanComputer extends Computer {

    private static final Logger logger = LoggerFactory.getLogger(PodmanComputer.class);

    public final String image;
    public final String username;      // 容器内用户名 = 员工名字的汉语拼音
    public final int uid;              // 容器内 uid (文件所有权区分)
    public final boolean isCeo;        // Public 目录属主 (CEO 的用户)
    public final String name;          // 角色中文名 (云盘个人目录名)
    public final String containerName;

    private boolean mcpPkgInstalled = false;

    public PodmanComputer(String roleId, String image, boolean autoMcp, String username,
                          int uid, String name) {
        super(roleId, autoMcp);
        this.image = image != null ? image : DEFAULT_IMAGE;
        this.username = (username == null || username.isEmpty()) ? "agent" : username;
        this.uid = uid <= 0 ? 1100 : uid;
        this.isCeo = (roleId == null ? "" : roleId).toUpperCase().equals("CEO");
        this.name = name != null ? name : "";
        this.containerName = "maf-" + (roleId == null || roleId.isEmpty() ? "shared" : roleId);
        if (findExecutable("podman") == null) {
            throw new RuntimeException(
                    "Podman 未安装, 无法创建角色 " + roleId + " 的电脑容器 (PodmanComputer 需要 podman; "
                            + "如需本地模拟请显式使用 create_computer(kind='local')).");
        }
    }

    /** 在 PATH 中查找可执行文件. */
    public static String findExecutable(String exe) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(":")) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Paths.get(dir, exe);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    /** 云盘个人目录名: 员工名字 (根目录文件夹 = 各角色名字). */
    private String driveDirName() {
        return !name.isEmpty() ? name : username;
    }

    @Override
    public String hostDir() {
        // 容器挂载的宿主机目录: data/computers/<role> ↔ 容器内 /home/<username>
        return Paths.get("./data/computers").toAbsolutePath().resolve(roleId == null || roleId.isEmpty() ? "shared" : roleId).toString();
    }

    @Override
    public String workdir() {
        return "/home/" + username;
    }

    /** 获取本电脑在自定义桥接网络 (maf-net) 中的 IP 地址. */
    public String getLanIp() {
        try {
            String fmt = "{{(index .NetworkSettings.Networks \"%s\").IPAddress}}"
                    .formatted(ComputerManager.getInstance().networkName);
            ProcessResult r = pod("inspect", containerName, "-f", fmt);
            String ip = (r.stdout == null ? "" : r.stdout).strip();
            return ip.isEmpty() ? "" : ip;
        } catch (Exception e) {
            logger.warn("电脑[{}] 获取内网 IP 失败", roleId);
            return "";
        }
    }

    @Override
    public List<String> installMcpServer() {
        // C 方案: MCP 服务器跑在容器内 (podman exec -i 保持 stdio 管道)
        if (mcpServer != null) {
            return listInstalledMcpTools();
        }
        if (!autoMcp) {
            logger.info("电脑[{}] 非自动创建, 不自动安装 MCP 服务器", roleId);
            return new ArrayList<>();
        }
        try {
            ensureContainer();  // 确保容器运行 + 包已预装
            mcpServer = new MCPServer(MCP_FILESYSTEM_PACKAGE,
                    List.of("/"),  // 授权容器内全部文件
                    "podman",
                    List.of("exec", "-i", "--user", username, containerName, "node",
                            "/usr/local/bin/mcp-server-filesystem", "/"));
            mcpServer.connect();
            if (!mcpServer.isAlive(5.0)) {
                throw new RuntimeException("容器内 MCP 服务器连接失败");
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
                        "mcp:" + MCP_FILESYSTEM_PACKAGE + " (容器内 " + containerName + ")");
                mcpTools.put(tname, td);
            }
            logger.info("电脑[{}] 容器内 MCP 服务器已安装, {} 个工具: {}",
                    roleId, mcpTools.size(), listInstalledMcpTools());
        } catch (Exception exc) {
            connectError = String.valueOf(exc.getMessage());
            logger.error("电脑[{}] 容器内 MCP 服务器安装失败", roleId, exc);
            return new ArrayList<>();
        }
        return listInstalledMcpTools();
    }

    public static final String MCP_FILESYSTEM_PACKAGE = "@modelcontextprotocol/server-filesystem";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        return new java.util.LinkedHashMap<>();
    }

    /** 执行 podman 命令 (默认 60s 超时: 容器操作均为秒级, 卡住应快速失败进入重试/报错, 而非静默数天). */
    protected ProcessResult pod(String... args) {
        return pod(60, args);
    }

    protected ProcessResult pod(int timeoutSeconds, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("podman");
        cmd.addAll(List.of(args));
        return runProcess(cmd, null, timeoutSeconds);
    }

    /**
     * 确保容器存在并运行 (不存在则创建), 并创建工作目录/用户/云盘目录.
     * 容器内用户名 = 员工名字的汉语拼音, 每员工一个固定 uid.
     */
    public void ensureContainer() {
        String hostDir = hostDir();
        Path driveHost = Paths.get(DRIVE_ROOT).toAbsolutePath();
        Path npmCacheHost = Paths.get("./data/computers").toAbsolutePath().resolve(".npm-cache");
        try {
            Files.createDirectories(Paths.get(hostDir));
            Files.createDirectories(driveHost);
            Files.createDirectories(npmCacheHost);
        } catch (IOException e) {
            throw new RuntimeException("创建目录失败: " + e.getMessage(), e);
        }

        // 确保基础镜像存在 (并发下加锁双检)
        String imageName = ComputerManager.getInstance().ensureBaseImage();

        ProcessResult r = pod("ps", "-a", "--format", "{{.Names}}");
        Set<String> names = new HashSet<>();
        if (r.stdout != null) {
            for (String line : r.stdout.split("\n")) {
                if (!line.isBlank()) {
                    names.add(line.strip());
                }
            }
        }
        if (!names.contains(containerName)) {
            String network = ComputerManager.getInstance().ensureNetwork();
            int attempt = 0;
            boolean ok = false;
            while (attempt < 3) {
                attempt++;
                r = pod("run", "-d", "--name", containerName, "--network", network,
                        "-v", hostDir + ":" + workdir(),
                        "-v", driveHost + ":/mnt/drive",
                        "-v", npmCacheHost + ":/root/.npm",
                        imageName, "sleep", "infinity");
                if (r.returnCode == 0) {
                    ok = true;
                    break;
                }
                logger.warn("电脑[{}] podman run 第 {} 次失败 ({}), 清理残留后重试",
                        roleId, attempt, truncate(r.stderr != null ? r.stderr : r.stdout, 200));
                pod("rm", "-f", containerName);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!ok) {
                throw new RuntimeException("podman run 创建容器失败 (" + r.returnCode + "): "
                        + truncate(r.stderr != null ? r.stderr : r.stdout, 300));
            }
        }
        r = pod("ps", "--filter", "name=" + containerName, "--format", "{{.Names}}");
        if (r.stdout == null || !r.stdout.contains(containerName)) {
            r = pod("start", containerName);
            if (r.returnCode != 0) {
                throw new RuntimeException("podman start 启动容器失败 (" + r.returnCode + "): "
                        + truncate(r.stderr != null ? r.stderr : r.stdout, 300));
            }
        }
        // 员工用户级初始化 (幂等, 毫秒级)
        String u = shlexQuote(username);
        String wd = shlexQuote(workdir());
        String setup = "id -u " + u + " >/dev/null 2>&1 || useradd -m -s /bin/bash -u " + uid
                + " -G sudo " + u + "; "
                + "[ -f /etc/sudoers.d/" + u + " ] || echo '" + username + " ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/" + u + "; "
                + "mkdir -p " + wd + "; chown -R " + uid + ":" + uid + " " + wd;
        r = pod("exec", containerName, "sh", "-c", setup);
        if (r.returnCode != 0) {
            throw new RuntimeException("podman exec 创建用户失败 (" + r.returnCode + "): "
                    + truncate(r.stderr != null ? r.stderr : r.stdout, 300));
        }
        // 企业云盘初始化
        String dname = shlexQuote("/mnt/drive/" + driveDirName());
        String driveInit = "mkdir -p /mnt/drive/Public " + dname + "; "
                + "chmod 777 /mnt/drive/Public; chmod 755 " + dname + "; "
                + "chown " + uid + ":" + uid + " " + dname;
        if (isCeo) {
            driveInit += "; chown " + uid + ":" + uid + " /mnt/drive/Public";
        }
        r = pod("exec", containerName, "sh", "-c", driveInit);
        if (r.returnCode != 0) {
            throw new RuntimeException("podman exec 初始化云盘失败 (" + r.returnCode + "): "
                    + truncate(r.stderr != null ? r.stderr : r.stdout, 300));
        }
        // 预装 MCP filesystem 服务器包 (容器内全局安装)
        if (!mcpPkgInstalled) {
            r = pod(300, "exec", containerName, "sh", "-c",
                    "npm ls -g --depth=0 2>/dev/null | grep -q 'server-filesystem' "
                            + "|| npm install -g --no-fund --no-audit " + shlexQuote(MCP_FILESYSTEM_PACKAGE));
            if (r.returnCode != 0) {
                throw new RuntimeException("容器内预装 MCP filesystem 包失败 (" + r.returnCode + "): "
                        + truncate(r.stderr != null ? r.stderr : r.stdout, 300));
            }
            mcpPkgInstalled = true;
            logger.info("电脑[{}] 容器内已预装 MCP filesystem 服务器 (npm -g)", roleId);
        }
    }

    /** shell 单引号引用 (shlex.quote 语义). */
    public static String shlexQuote(String s) {
        if (s == null || s.isEmpty()) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    @Override
    public String powerOn() {
        try {
            ensureContainer();
            on = true;
            // 跨天重连: 容器 stop 会杀死 MCP stdio 管道, 开机后检测会话存活
            reconnectMcpServer();
            return "电脑[" + roleId + "] (podman 容器 " + containerName + ") 已开机. 工作目录: " + workdir();
        } catch (Exception exc) {
            return "错误: 开机失败 - " + exc.getMessage();
        }
    }

    @Override
    public String powerOff() {
        try {
            pod("stop", containerName);
            on = false;
            return "电脑[" + roleId + "] (podman) 已关机.";
        } catch (Exception exc) {
            return "错误: 关机失败 - " + exc.getMessage();
        }
    }

    @Override
    public String runCommand(String command, int timeout, int maxChars) {
        if (!on) {
            return "错误: 电脑未开机.";
        }
        try {
            // 以员工用户执行: 云盘/家目录权限按该用户判定
            ProcessResult r = pod(timeout, "exec", "--user", username, containerName, "sh", "-c", command);
            return formatResult(r, maxChars);
        } catch (Exception exc) {
            return "错误: 命令执行失败 - " + exc.getMessage();
        }
    }

    @Override
    public String readFile(String path) {
        // 路径经 argv 传入 (sh -c 的 $1), 不经 shell 解析 — 无注入面
        return execArgv("cat -- \"$1\"", path);
    }

    @Override
    public String writeFile(String path, String content) {
        if (!on) {
            return "错误: 电脑未开机.";
        }
        String parent = path.contains("/") ? Paths.get(path).getParent().toString() : ".";
        ProcessResult r = runProcess(List.of("podman", "exec", "-i", "--user", username,
                containerName, "sh", "-c", "mkdir -p -- \"$2\" && cat > \"$1\"", "sh", path, parent),
                content, 60);
        String output = ((r.stdout == null ? "" : r.stdout) + (r.stderr == null ? "" : r.stderr)).strip();
        if (r.returnCode != 0) {
            return "[exit " + r.returnCode + "] " + truncate(output, 2000);
        }
        return output.isEmpty() ? "(无输出)" : truncate(output, 2000);
    }

    /** 以 argv 方式执行容器内命令 (脚本 + 参数分离, 路径不经 shell 解析). */
    protected String execArgv(String script, String... args) {
        if (!on) {
            return "错误: 电脑未开机.";
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("podman");
        cmd.add("exec");
        cmd.add("--user");
        cmd.add(username);
        cmd.add(containerName);
        cmd.add("sh");
        cmd.add("-c");
        cmd.add(script);
        cmd.add("sh");
        cmd.addAll(List.of(args));
        ProcessResult r = runProcess(cmd, null, 60);
        String output = ((r.stdout == null ? "" : r.stdout) + (r.stderr == null ? "" : r.stderr)).strip();
        if (r.returnCode != 0) {
            return "[exit " + r.returnCode + "] " + truncate(output, 2000);
        }
        return output.isEmpty() ? "(无输出)" : truncate(output, 2000);
    }

    @Override
    public String listDir(String path) {
        String target = (path == null || path.isEmpty()) ? workdir() : path;
        return execArgv("ls -la -- \"$1\"", target);
    }

    @Override
    public String deleteFile(String path) {
        return execArgv("rm -f -- \"$1\"", path);
    }

    @Override
    public String describe() {
        return "电脑[" + roleId + "] (podman 容器 " + containerName + "): "
                + "状态=" + (on ? "开机" : "关机") + ", 工作目录=" + workdir();
    }
}
