package com.maf.scheduler.core;

import com.maf.scheduler.utils.Json;
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
 * MCP 服务器客户端 (Python 版 mcp_client.py 的 Java 对应物).
 *
 * 通过 npx 或自定义命令 (如容器内 podman exec) 启动服务器进程, 用
 * newline-delimited JSON-RPC 2.0 走 stdio 传输: 后台线程读服务器输出,
 * 工具调用按 request id 匹配响应.
 */
public class MCPServer {

    private static final Logger logger = LoggerFactory.getLogger(MCPServer.class);

    private static final String PROTOCOL_VERSION = "2024-11-05";

    public final String packageName;
    public final List<String> args;
    public final String command;          // 自定义启动命令 (如 "podman")
    public final List<String> commandArgs; // 自定义命令参数 (podman exec -i ...)

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

    // ── 生命周期 ──────────────────────────────────────────

    /** 启动服务器进程并建立会话 (最多等待 20 秒). */
    public synchronized void connect() {
        try {
            List<String> cmd = new ArrayList<>();
            if (command != null && !command.isEmpty()) {
                // 自定义启动命令 (容器内执行等): 直接 spawn, 不经过宿主 npx
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

            // 握手: initialize → initialized
            Map<String, Object> resp = request("initialize", Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", new LinkedHashMap<>(),
                    "clientInfo", Map.of("name", "maf-java", "version", "1.0.0")));
            if (resp == null) {
                throw new IOException("initialize 无响应: " + (connectError != null ? connectError : "未知错误"));
            }
            notify("notifications/initialized", new LinkedHashMap<>());
            connected = true;
            logger.info("MCP 服务器 '{}' 连接成功", packageName);
        } catch (Exception exc) {
            connectError = String.valueOf(exc.getMessage());
            logger.error("MCP 服务器 '{}' 连接失败: {}", packageName, exc.getMessage());
            connected = false;
        }
    }

    /** 关闭服务器连接. */
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
     * 探测服务器会话是否仍然可用 (进程死亡/管道断裂后返回 false).
     * 走一次轻量 list_tools 往返.
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

    /** 最近一次连接/请求失败原因 (诊断用). */
    public String connectError() {
        return connectError;
    }

    // ── 工具操作 ──────────────────────────────────────────

    /** 获取服务器工具列表. 返回 MCP 工具 Map 列表 (name/description/inputSchema). */
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
            logger.error("MCP '{}' list_tools 失败: {}", packageName, exc.getMessage());
            return new ArrayList<>();
        }
    }

    /** 调用服务器上的工具. 返回结果文本. */
    public String callTool(String name, Map<String, Object> arguments) {
        if (!connected) {
            return "错误: MCP 服务器 '" + packageName + "' 未连接";
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", name);
            params.put("arguments", arguments != null ? arguments : new LinkedHashMap<>());
            Map<String, Object> result = request("tools/call", params);
            if (result == null) {
                return "错误: 调用 " + name + " 失败 - " + (connectError != null ? connectError : "无响应");
            }
            // 提取文本内容
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
                return "[MCP 错误] " + parts;
            }
            return parts.length() == 0 ? String.valueOf(result) : parts.toString();
        } catch (Exception exc) {
            logger.error("MCP '{}' 调用 {} 失败: {}", packageName, name, exc.getMessage());
            return "错误: 调用 " + name + " 失败 - " + exc.getMessage();
        }
    }

    // ── 内部: JSON-RPC over stdio ─────────────────────────

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
            throw new IOException("MCP 请求超时/失败: " + method + " - " + e.getMessage());
        }
    }

    private synchronized void sendLine(Map<String, Object> msg) throws IOException {
        if (stdin == null || process == null || !process.isAlive()) {
            throw new IOException("MCP 进程未运行");
        }
        stdin.write(Json.stringify(msg));
        stdin.write("\n");
        stdin.flush();
    }

    /** 后台读取线程: 解析服务器的 JSON-RPC 响应/通知. */
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
            logger.debug("MCP '{}' 读取流结束: {}", packageName, e.getMessage());
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
                logger.debug("MCP '{}' 通知: {}", packageName, line);
            }
        } catch (IOException e) {
            logger.warn("MCP '{}' 收到非法 JSON 行: {}", packageName, e.getMessage());
        }
    }
}
