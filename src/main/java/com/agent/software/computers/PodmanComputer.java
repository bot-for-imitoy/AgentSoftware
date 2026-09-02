package com.agent.software.computers;

import com.agent.software.core.MCPServer;
import com.agent.software.role.ToolRegistry;
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
 * Podman container virtual computer (default implementation) — the Java counterpart of the Python PodmanComputer.
 *
 * Each role gets one container (named maf-&lt;role_id&gt;); commands run via podman exec.
 * Requires podman installed on the host; the constructor throws RuntimeException if it is missing.
 */
public class PodmanComputer extends Computer {

    private static final Logger logger = LoggerFactory.getLogger(PodmanComputer.class);

    public final String image;
    public final String username;      // in-container username = pinyin of the employee name
    public final int uid;              // in-container uid (distinguishes file ownership)
    public final boolean isCeo;        // owner of the Public dir (the CEO's user)
    public final String name;          // role display name (kept for reference)
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
                    "Podman is not installed; cannot create the computer container for role " + roleId
                            + " (PodmanComputer requires podman; "
                            + "for local emulation, explicitly use create_computer(kind='local')).");
        }
    }

    /** Finds an executable on the PATH. */
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

    /** Cloud drive personal directory name: the employee's username (each role's own folder). */
    private String driveDirName() {
        return !username.isEmpty() ? username : name;
    }

    @Override
    public String hostDir() {
        // Host directory mounted into the container: data/computers/<role> ↔ in-container /home/<username>
        return Paths.get("./data/computers").toAbsolutePath().resolve(roleId == null || roleId.isEmpty() ? "shared" : roleId).toString();
    }

    @Override
    public String workdir() {
        return "/home/" + username;
    }

    /** Gets this computer's IP address on the custom bridge network (maf-net). */
    public String getLanIp() {
        try {
            String fmt = "{{(index .NetworkSettings.Networks \"%s\").IPAddress}}"
                    .formatted(ComputerManager.getInstance().networkName);
            ProcessResult r = pod("inspect", containerName, "-f", fmt);
            String ip = (r.stdout == null ? "" : r.stdout).strip();
            return ip.isEmpty() ? "" : ip;
        } catch (Exception e) {
            logger.warn("Computer[{}] failed to get in-container IP", roleId);
            return "";
        }
    }

    @Override
    public List<String> installMcpServer() {
        // Plan C: the MCP server runs inside the container (podman exec -i keeps the stdio pipe)
        if (mcpServer != null) {
            return listInstalledMcpTools();
        }
        if (!autoMcp) {
            logger.info("Computer[{}] not auto-created; skipping automatic MCP server install", roleId);
            return new ArrayList<>();
        }
        try {
            ensureContainer();  // ensure the container is running + the package is preinstalled
            mcpServer = new MCPServer(MCP_FILESYSTEM_PACKAGE,
                    List.of("/"),  // authorize all files inside the container
                    "podman",
                    List.of("exec", "-i", "--user", username, containerName, "node",
                            "/usr/local/bin/mcp-server-filesystem", "/"));
            mcpServer.connect();
            if (!mcpServer.isAlive(5.0)) {
                throw new RuntimeException("Failed to connect to the in-container MCP server");
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
                        "mcp:" + MCP_FILESYSTEM_PACKAGE + " (inside container " + containerName + ")");
                mcpTools.put(tname, td);
            }
            logger.info("Computer[{}] in-container MCP server installed, {} tools: {}",
                    roleId, mcpTools.size(), listInstalledMcpTools());
        } catch (Exception exc) {
            connectError = String.valueOf(exc.getMessage());
            logger.error("Computer[{}] failed to install the in-container MCP server", roleId, exc);
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

    /** Runs a podman command (default 60s timeout: container operations are sub-second; hanging should fail fast into retry/error, not silently wait). */
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
     * Ensures the container exists and is running (creates it if missing), and sets up the
     * working directory/user/cloud drive dir. The in-container username is the pinyin of the
     * employee name; each employee gets a fixed uid.
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
            throw new RuntimeException("Failed to create directories: " + e.getMessage(), e);
        }

        // Ensure the base image exists (double-checked locking under concurrency)
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
                logger.warn("Computer[{}] podman run attempt {} failed ({}); cleaning up leftovers and retrying",
                        roleId, attempt, truncate(r.stderr != null ? r.stderr : r.stdout, 200));
                pod("rm", "-f", containerName);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!ok) {
                throw new RuntimeException("podman run failed to create the container (" + r.returnCode + "): "
                        + truncate(r.stderr != null ? r.stderr : r.stdout, 300));
            }
        }
        r = pod("ps", "--filter", "name=" + containerName, "--format", "{{.Names}}");
        if (r.stdout == null || !r.stdout.contains(containerName)) {
            r = pod("start", containerName);
            if (r.returnCode != 0) {
                throw new RuntimeException("podman start failed to start the container (" + r.returnCode + "): "
                        + truncate(r.stderr != null ? r.stderr : r.stdout, 300));
            }
        }
        // Employee user-level initialization (idempotent, millisecond-level)
        String u = shlexQuote(username);
        String wd = shlexQuote(workdir());
        String setup = "id -u " + u + " >/dev/null 2>&1 || useradd -s /bin/bash -u " + uid
                + " -G sudo " + u + "; "
                + "[ -f /etc/sudoers.d/" + u + " ] || echo '" + username + " ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/" + u + "; "
                + "mkdir -p " + wd + "; chown -R " + uid + ":" + uid + " " + wd;
        r = pod("exec", containerName, "sh", "-c", setup);
        if (r.returnCode != 0) {
            throw new RuntimeException("podman exec failed to create the user (" + r.returnCode + "): "
                    + truncate(r.stderr != null ? r.stderr : r.stdout, 300));
        }
        // Company cloud drive initialization
        String dname = shlexQuote("/mnt/drive/" + driveDirName());
        String driveInit = "mkdir -p /mnt/drive/Public " + dname + "; "
                + "chmod 777 /mnt/drive/Public; chmod 755 " + dname + "; "
                + "chown " + uid + ":" + uid + " " + dname;
        if (isCeo) {
            driveInit += "; chown " + uid + ":" + uid + " /mnt/drive/Public";
        }
        r = pod("exec", containerName, "sh", "-c", driveInit);
        if (r.returnCode != 0) {
            throw new RuntimeException("podman exec failed to initialize the cloud drive (" + r.returnCode + "): "
                    + truncate(r.stderr != null ? r.stderr : r.stdout, 300));
        }
        // Preinstall the MCP filesystem server package (globally inside the container)
        if (!mcpPkgInstalled) {
            r = pod(300, "exec", containerName, "sh", "-c",
                    "npm ls -g --depth=0 2>/dev/null | grep -q 'server-filesystem' "
                            + "|| npm install -g --no-fund --no-audit " + shlexQuote(MCP_FILESYSTEM_PACKAGE));
            if (r.returnCode != 0) {
                throw new RuntimeException("Failed to preinstall the MCP filesystem package in the container (" + r.returnCode + "): "
                        + truncate(r.stderr != null ? r.stderr : r.stdout, 300));
            }
            mcpPkgInstalled = true;
            logger.info("Computer[{}] MCP filesystem server preinstalled in container (npm -g)", roleId);
        }
    }

    /** Shell single-quote quoting (shlex.quote semantics). */
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
            // Reconnect across days: stopping the container kills the MCP stdio pipe, so check session liveness after power-on
            reconnectMcpServer();
            return "Computer [" + roleId + "] (podman container " + containerName + ") powered on. Work directory: " + workdir();
        } catch (Exception exc) {
            return "Error: power-on failed - " + exc.getMessage();
        }
    }

    @Override
    public String powerOff() {
        try {
            pod("stop", containerName);
            on = false;
            return "Computer [" + roleId + "] (podman) powered off.";
        } catch (Exception exc) {
            return "Error: power-off failed - " + exc.getMessage();
        }
    }

    @Override
    public String runCommand(String command, int timeout, int maxChars) {
        if (!on) {
            return "Error: computer is not powered on.";
        }
        try {
            // Run as the employee user: cloud drive / home dir permissions are judged for that user
            ProcessResult r = pod(timeout, "exec", "--user", username, containerName, "sh", "-c", command);
            return formatResult(r, maxChars);
        } catch (Exception exc) {
            return "Error: command execution failed - " + exc.getMessage();
        }
    }

    @Override
    public String readFile(String path) {
        // Path is passed via argv ($1 of sh -c), not parsed by the shell — no injection surface
        return execArgv("cat -- \"$1\"", path);
    }

    @Override
    public String writeFile(String path, String content) {
        if (!on) {
            return "Error: computer is not powered on.";
        }
        String parent = path.contains("/") ? Paths.get(path).getParent().toString() : ".";
        ProcessResult r = runProcess(List.of("podman", "exec", "-i", "--user", username,
                containerName, "sh", "-c", "mkdir -p -- \"$2\" && cat > \"$1\"", "sh", path, parent),
                content, 60);
        String output = ((r.stdout == null ? "" : r.stdout) + (r.stderr == null ? "" : r.stderr)).strip();
        if (r.returnCode != 0) {
            return "[exit " + r.returnCode + "] " + truncate(output, 2000);
        }
        return output.isEmpty() ? "(no output)" : truncate(output, 2000);
    }

    /** Runs an in-container command via argv (script + args separated, paths not parsed by the shell). */
    protected String execArgv(String script, String... args) {
        if (!on) {
            return "Error: computer is not powered on.";
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
        return output.isEmpty() ? "(no output)" : truncate(output, 2000);
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
        return "Computer [" + roleId + "] (podman container " + containerName + "): "
                + "status=" + (on ? "powered on" : "powered off") + ", work directory=" + workdir();
    }
}
