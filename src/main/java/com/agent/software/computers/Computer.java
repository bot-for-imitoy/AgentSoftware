package com.agent.software.computers;

import com.agent.software.core.MCPServer;
import com.agent.software.role.ToolRegistry;
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
 * Computer base class and implementations (Computer) - one personal computer per role (Python version computer.py).
 *
 * Three implementations:
 *   - {@link LocalComputer}: local directory simulation (data/computers/&lt;role_id&gt;/).
 *   - {@link PodmanComputer}: podman container virtual computer (default; container name maf-&lt;role_id&gt;).
 *   - {@link SSHComputer}: executes commands on a remote host over ssh.
 */
public abstract class Computer {

    private static final Logger logger = LoggerFactory.getLogger(Computer.class);

    // ── Path constants (referenced uniformly at usage sites) ──────────────────────────────
    public static final String COMPUTERS_ROOT = "./data/computers";
    public static final String DRIVE_ROOT = "./data/drive";

    // Default container image for computers: defined by the Containerfile at the project root
    public static final String DEFAULT_IMAGE = "maf-base:latest";
    public static final String CONTAINERFILE = "Containerfile";

    public final String roleId;
    protected boolean on = false;
    protected final boolean autoMcp;              // auto-created computers: automatically install the MCP server at creation time
    protected final Map<String, ToolRegistry.ToolDef> mcpTools = new LinkedHashMap<>();
    protected final MCPServer mcpServer = null;          // this computer's own MCP server connection (lazily created)
    protected String connectError = null;          // reason for the most recent MCP connection failure (for diagnostics)

    protected Computer(String roleId, boolean autoMcp) {
        this.roleId = roleId;
        this.autoMcp = autoMcp;
    }

    // ── MCP session liveness check and reconnect ───────────────────────────

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

    /** Rebuild the MCP server session when it becomes invalid (podman stop after a cross-day shutdown kills the stdio pipe). */
    protected void reconnectMcpServer() {
        if (mcpServer == null){
            logger.error("MCPServer is null");
            return;
        }
        if (mcpServerAlive()) {
            return;
        }
        logger.warn("Reconnecting MCP Sever in computer [{}] ...", roleId);
        try {
            mcpServer.close();
        } catch (Exception ignored) {
        }
        mcpTools.clear();  // the old handlers are bound to the dead server
        try {
            mcpServer.connect();
        } catch (Exception e) {
            logger.error("Computer [{}] MCP server rebuild failed", roleId, e);
        }
    }

    // ── Abstract interface (implemented by subclasses) ──────────────────────────────

    public abstract String powerOn();

    public abstract String powerOff();

    /** Run a command (on the personal computer). Returns the command output. */
    public abstract String runCommand(String command, int timeout, int maxChars);

    public abstract String readFile(String path);

    /** Write a file on the personal computer (auto-creates parent directories). Returns the path. */
    public abstract String writeFile(String path, String content);

    /** List the contents of a directory on the personal computer (defaults to the work directory). */
    public abstract String listDir(String path);

    /** Delete a file on the personal computer. Returns a status description. */
    public abstract String deleteFile(String path);

    /** Uniformly format a command execution result (exit code + truncated output). */
    protected String formatResult(ProcessResult r, int maxChars) {
        String output = (r.stdout == null ? "" : r.stdout) + (r.stderr == null ? "" : r.stderr);
        output = output.strip();
        if (r.returnCode != 0) {
            return "[exit " + r.returnCode + "] " + truncate(output, maxChars);
        }
        return truncate(output, maxChars).isEmpty() ? "(no output)" : truncate(output, maxChars);
    }

    protected static String truncate(String s, int n) {
        if (s == null || s.length() <= n) {
            return s == null ? "" : s;
        }
        return s.substring(0, n);
    }

    /** Process execution result (stdout/stderr/returncode). */
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

    /** Execute a process, returning (stdout+stderr, returncode). */
    protected static ProcessResult runProcess(List<String> cmd, String stdinInput,
                                              int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process p = pb.start();

            StringBuilder stdoutBuffer = new StringBuilder();
            StringBuilder stderrBuffer = new StringBuilder();

            // 1. Read stdout asynchronously in real time
            Thread stdoutThread = Thread.ofVirtual().start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println(line); // immediate console output
                        stdoutBuffer.append(line).append("\n"); // accumulate into the log
                    }
                } catch (IOException ignored) {}
            });

            // 2. Read stderr asynchronously in real time
            Thread stderrThread = Thread.ofVirtual().start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.err.println(line); // immediate standard error output
                        stderrBuffer.append(line).append("\n");
                    }
                } catch (IOException ignored) {}
            });

            // 3. Write to stdin
            if (stdinInput != null) {
                try (var w = p.outputWriter(StandardCharsets.UTF_8)) {
                    w.write(stdinInput);
                }
            }

            // 4. Wait for the child process to finish and ensure the reader threads terminate
            boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            int rc;
            if (!done) {
                p.destroyForcibly();
                rc = -1;
            } else {
                rc = p.exitValue();
            }

            // 5. Wait for the reader threads to finish (bounded join). After the child process exits the pipe usually
            //    EOFs quickly; but podman's descendant processes (conmon / container processes) may still hold the pipe's
            //    write end, making readLine block forever - a join without a timeout would deadlock runProcess itself
            //    (combined with the former 600000s timeout in pod() = console silent for nearly 7 days).
            long joinDeadline = System.currentTimeMillis() + 30_000;
            stdoutThread.join(Math.max(1, joinDeadline - System.currentTimeMillis()));
            stderrThread.join(Math.max(1, joinDeadline - System.currentTimeMillis()));

            return new ProcessResult(stdoutBuffer.toString(), stderrBuffer.toString(), rc);
        } catch (IOException e) {
            return new ProcessResult("", "Error: process failed to start - " + e.getMessage(), -2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessResult("", "Error: process interrupted", -3);
        }
    }

    public boolean isAutoMcp() {
        return autoMcp;
    }

    // ── MCP tool installation and execution (shared by all implementations) ────────────────

    /** Mapped path of this computer's work directory on the host (the MCP server's authorized directory). */
    public String hostDir() {
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        return new LinkedHashMap<>();
    }

    /** Uninstall one MCP tool from this computer. Returns whether the uninstall succeeded. */
    public boolean uninstallMcpTool(String toolName) {
        return mcpTools.remove(toolName) != null;
    }

    /** List the MCP tool names installed on this computer (sorted). */
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

    /** Run an MCP tool (executed on this computer). */
    public String runMcpTool(String toolName, Map<String, Object> args) {
        ToolRegistry.ToolDef td = mcpTools.get(toolName);
        if (td == null) {
            return "Error: MCP tool '" + toolName + "' is not installed on this computer. Installed: "
                    + (listInstalledMcpTools().isEmpty() ? "(none)" : listInstalledMcpTools())
                    + ". Use mcp_search / mcp_list to view available tools, and mcp_add to install.";
        }
        if (td.handler == null) {
            return "Error: tool '" + toolName + "' has no executable handler.";
        }
        try {
            return String.valueOf(td.handler.handle(args));
        } catch (Exception exc) {
            logger.error("MCP tool {} execution failed", toolName, exc);
            return "Error: tool '" + toolName + "' execution failed - " + exc.getMessage();
        }
    }

    // ── General ──────────────────────────────────────────────

    public boolean isOn() {
        return on;
    }

    /** Reboot the computer (power off, then power on). Common to all implementations. */
    public String reboot() {
        String off = powerOff();
        String on = powerOn();
        return "Computer [" + roleId + "] has been rebooted.\n- " + off + "\n- " + on;
    }

    /** Personal work directory (a path on the computer). Subclasses may override. */
    public String workdir() {
        return "/home/agent";
    }

    /** Enterprise cloud drive mount root path. */
    public String driveRoot() {
        return "/mnt/drive";
    }

    /** Computer status description (for the LLM to view). */
    public String describe() {
        return "Computer [" + roleId + "] (" + getClass().getSimpleName() + "): "
                + "status=" + (on ? "powered on" : "powered off") + ", work directory=" + workdir();
    }

    // ── LocalComputer (local directory simulation) ──────────────────────────

    /** Local directory simulation computer (for development/fallback). */
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
                throw new RuntimeException("Failed to create local computer directory: " + dir, e);
            }
            this.driveRoot = Paths.get(driveDir != null ? driveDir : DRIVE_ROOT).toAbsolutePath().toString();
            this.on = true;  // local simulation powers on by default
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
            return "Computer [" + roleId + "] (local simulation) powered on. Work directory: " + dir;
        }

        @Override
        public String powerOff() {
            on = false;
            return "Computer [" + roleId + "] (local simulation) powered off.";
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
                return "Error: computer is not powered on.";
            }
            ProcessResult r = runProcess(List.of("sh", "-c", command), null, timeout);
            return formatResult(r, maxChars);
        }

        @Override
        public String readFile(String path) {
            Path p = resolve(path);
            if (!Files.exists(p)) {
                return "File not found: " + p;
            }
            try {
                return Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "Error: read failed - " + e.getMessage();
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
                return "Error: write failed - " + e.getMessage();
            }
        }

        @Override
        public String listDir(String path) {
            Path p = resolve(path == null || path.isEmpty() ? "" : path);
            if (!Files.exists(p) || !Files.isDirectory(p)) {
                return "Directory not found: " + p;
            }
            try (var stream = Files.list(p)) {
                List<String> names = new ArrayList<>();
                stream.forEach(f -> names.add(f.getFileName().toString()));
                names.sort(String::compareTo);
                return names.isEmpty() ? "(empty directory)" : String.join("\n", names);
            } catch (IOException e) {
                return "Error: failed to list directory - " + e.getMessage();
            }
        }

        @Override
        public String deleteFile(String path) {
            Path p = resolve(path);
            if (!Files.exists(p)) {
                return "File not found: " + p;
            }
            try {
                Files.delete(p);
                return "Deleted: " + p;
            } catch (IOException e) {
                return "Error: delete failed - " + e.getMessage();
            }
        }
    }
}
