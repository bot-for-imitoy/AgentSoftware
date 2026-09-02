package com.agent.software.core;

import com.agent.software.utils.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP server client (the Java counterpart of the Python mcp_client.py).
 *
 * Starts the server process via npx or a custom command (e.g. podman exec inside a container), using
 * newline-delimited JSON-RPC 2.0 over stdio: a background thread reads the server output,
 * and tool calls are matched to responses by request id.
 */
public class MCPServer {

    private static final Logger logger = LoggerFactory.getLogger(MCPServer.class);

    private static final String PROTOCOL_VERSION = "2024-11-05";

    public final String packageName;
    public final List<String> args;
    public final String command;          // custom launch command (e.g. "podman")
    public final List<String> commandArgs; // custom command arguments (podman exec -i ...)

    private Process process;
    private Writer stdin;
    private Thread readerThread;
    private volatile boolean connected = false;
    private String connectError = null;
    private final AtomicInteger nextId = new AtomicInteger(0);
    private final Map<Integer, CompletableFuture<Map<String, Object>>> pending =
            new ConcurrentHashMap<>();

    public MCPServer(String packageName, List<String> args, String command, List<String> commandArgs) {
        this.packageName = packageName;
        this.args = args != null ? args : new ArrayList<>();
        this.command = command;
        this.commandArgs = commandArgs != null ? commandArgs : new ArrayList<>();
    }

    public MCPServer(String packageName, List<String> args) {
        this(packageName, args, null, null);
    }

    // ── Lifecycle ─────────────────────────────────────────

    /** Start the server process and establish a session (waits up to 20 seconds). */
    public synchronized void connect() {
        try {
            List<String> cmd = new ArrayList<>();
            if (command != null && !command.isEmpty()) {
                // custom launch command (e.g. running inside a container): spawn directly, bypassing the host npx
                cmd.add(command);
                cmd.addAll(commandArgs);
            } else {
                cmd.add("npx");
                cmd.add("-y");
                cmd.add(packageName);
                cmd.addAll(args);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            process = pb.start();
            stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            readerThread = new Thread(this::readLoop, "mcp-" + packageName);
            readerThread.setDaemon(true);
            readerThread.start();

            // handshake: initialize → initialized
            Map<String, Object> resp = request("initialize", Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", new LinkedHashMap<>(),
                    "clientInfo", Map.of("name", "maf-java", "version", "1.0.0")));
            if (resp == null) {
                throw new IOException("initialize got no response: " + (connectError != null ? connectError : "unknown error"));
            }
            notify("notifications/initialized", new LinkedHashMap<>());
            connected = true;
            logger.info("MCP server '{}' connected successfully", packageName);
        } catch (Exception exc) {
            connectError = String.valueOf(exc.getMessage());
            logger.error("MCP server '{}' connection failed: {}", packageName, exc.getMessage());
            connected = false;
        }
    }

    /** Close the server connection. */
    public synchronized void close() {
        connected = false;
        try {
            if (process != null) {
                process.destroy();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            process = null;
            stdin = null;
            for (CompletableFuture<Map<String, Object>> f : pending.values()) {
                f.complete(null);
            }
            pending.clear();
        }
    }

    /**
     * Probe whether the server session is still usable (returns false after process death / broken pipe).
     * Performs one lightweight list_tools round trip.
     */
    public boolean isAlive(double timeoutSeconds) {
        if (!connected || process == null || !process.isAlive()) {
            return false;
        }
        try {
            Map<String, Object> result = request("tools/list", new LinkedHashMap<>(),
                    (long) (timeoutSeconds * 1000));
            return result != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Reason of the most recent connection/request failure (for diagnostics). */
    public String connectError() {
        return connectError;
    }

    // ── Tool operations ───────────────────────────────────

    /** Get the server's tool list. Returns a list of MCP tool Maps (name/description/inputSchema). */
    public List<Map<String, Object>> listTools() {
        if (!connected) {
            return new ArrayList<>();
        }
        try {
            Map<String, Object> result = request("tools/list", new LinkedHashMap<>());
            if (result == null) {
                return new ArrayList<>();
            }
            Object tools = result.get("tools");
            if (tools instanceof List<?> list) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) o;
                        out.add(m);
                    }
                }
                return out;
            }
            return new ArrayList<>();
        } catch (Exception exc) {
            logger.error("MCP '{}' list_tools failed: {}", packageName, exc.getMessage());
            return new ArrayList<>();
        }
    }

    /** Call a tool on the server. Returns the result text. */
    public String callTool(String name, Map<String, Object> arguments) {
        if (!connected) {
            return "Error: MCP server '" + packageName + "' is not connected";
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", name);
            params.put("arguments", arguments != null ? arguments : new LinkedHashMap<>());
            Map<String, Object> result = request("tools/call", params);
            if (result == null) {
                return "Error: calling " + name + " failed - " + (connectError != null ? connectError : "no response");
            }
            // extract text content
            StringBuilder parts = new StringBuilder();
            Object content = result.get("content");
            if (content instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Object text = m.get("text");
                        if (text != null) {
                            parts.append(text);
                        }
                    }
                }
            }
            if (Boolean.TRUE.equals(result.get("isError"))) {
                return "[MCP Error] " + parts;
            }
            return parts.length() == 0 ? String.valueOf(result) : parts.toString();
        } catch (Exception exc) {
            logger.error("MCP '{}' call {} failed: {}", packageName, name, exc.getMessage());
            return "Error: calling " + name + " failed - " + exc.getMessage();
        }
    }

    // ── Internal: JSON-RPC over stdio ─────────────────────

    private void notify(String method, Map<String, Object> params) throws IOException {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        msg.put("params", params);
        sendLine(msg);
    }

    private Map<String, Object> request(String method, Map<String, Object> params) throws IOException {
        return request(method, params, 60_000);
    }

    private Map<String, Object> request(String method, Map<String, Object> params,
                                        long timeoutMillis) throws IOException {
        int id = nextId.incrementAndGet();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        msg.put("params", params);
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);
        sendLine(msg);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pending.remove(id);
            throw new IOException("MCP request timed out/failed: " + method + " - " + e.getMessage());
        }
    }

    private synchronized void sendLine(Map<String, Object> msg) throws IOException {
        if (stdin == null || process == null || !process.isAlive()) {
            throw new IOException("MCP process is not running");
        }
        stdin.write(Json.stringify(msg));
        stdin.write("\n");
        stdin.flush();
    }

    /** Background reader thread: parses the server's JSON-RPC responses/notifications. */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                handleMessage(line);
            }
        } catch (IOException e) {
            logger.debug("MCP '{}' read stream ended: {}", packageName, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void handleMessage(String line) {
        try {
            Map<String, Object> msg = Json.parseObject(line);
            Object id = msg.get("id");
            if (id instanceof Number n) {
                CompletableFuture<Map<String, Object>> future = pending.remove(n.intValue());
                if (future != null) {
                    if (msg.containsKey("result")) {
                        future.complete((Map<String, Object>) msg.get("result"));
                    } else if (msg.containsKey("error")) {
                        connectError = String.valueOf(msg.get("error"));
                        future.complete(null);
                    } else {
                        future.complete(null);
                    }
                }
            } else {
                logger.debug("MCP '{}' notification: {}", packageName, line);
            }
        } catch (IOException e) {
            logger.warn("MCP '{}' received invalid JSON line: {}", packageName, e.getMessage());
        }
    }
}
