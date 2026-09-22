package com.agent.software.computers;

import com.agent.software.utils.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 一个 MCP stdio 会话：JSON-RPC over stdin/stdout。
 *
 * <p>启动命令可以自定义（容器内 npx/node）；{@link #connect()} 做 initialize 握手并缓存工具列表。
 */
public class MCPServer {

    private static final Logger logger = LoggerFactory.getLogger(MCPServer.class);

    public final String packageName;
    public final List<String> args;
    public final String command;
    public final List<String> commandArgs;

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private long nextId = 1;
    private final List<Map<String, Object>> tools = new ArrayList<>();
    private String connectError;

    public MCPServer(String packageName, List<String> args) {
        this(packageName, args, null, null);
    }

    public MCPServer(String packageName, List<String> args, String command, List<String> commandArgs) {
        this.packageName = packageName;
        this.args = args == null ? new ArrayList<>() : new ArrayList<>(args);
        this.command = command;
        this.commandArgs = commandArgs == null ? new ArrayList<>() : new ArrayList<>(commandArgs);
    }

    public synchronized void connect() throws IOException {
        if (isAlive()) {
            return;
        }
        List<String> cmd = new ArrayList<>();
        if (command != null && !command.isBlank()) {
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
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", Map.of());
        Map<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", "agentsoftware");
        clientInfo.put("version", "1.0.0");
        params.put("clientInfo", clientInfo);
        request("initialize", params);
        notify("notifications/initialized", Map.of());
        tools.clear();
        Object result = request("tools/list", Map.of());
        if (result instanceof Map<?, ?> m && m.get("tools") instanceof List<?> list) {
            for (Object t : list) {
                if (t instanceof Map<?, ?> tm) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : tm.entrySet()) {
                        copy.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    tools.add(copy);
                }
            }
        }
        connectError = null;
        logger.info("MCPServer[{}] connected ({} tools)", packageName, tools.size());
    }

    public synchronized void close() {
        try {
            if (process != null) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            process = null;
            stdin = null;
            stdout = null;
        }
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public List<Map<String, Object>> listTools() {
        return List.copyOf(tools);
    }

    public synchronized String callTool(String name, Map<String, Object> arguments) throws IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        Object result = request("tools/call", params);
        if (result instanceof Map<?, ?> m) {
            Object content = m.get("content");
            if (content instanceof List<?> list) {
                StringBuilder sb = new StringBuilder();
                for (Object c : list) {
                    if (c instanceof Map<?, ?> cm && cm.get("text") != null) {
                        sb.append(cm.get("text"));
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
            return Json.stringify(m);
        }
        return String.valueOf(result);
    }

    private Object request(String method, Map<String, Object> params) throws IOException {
        long id = nextId++;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        msg.put("params", params);
        send(msg);
        String line;
        while ((line = stdout.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> resp;
            try {
                resp = Json.parseObject(line);
            } catch (Exception e) {
                continue;
            }
            Object respId = resp.get("id");
            if (respId instanceof Number n && n.longValue() == id) {
                if (resp.containsKey("error")) {
                    throw new IOException("MCP error: " + Json.stringify(resp.get("error")));
                }
                return resp.get("result");
            }
        }
        connectError = "MCP server closed the stream";
        throw new IOException(connectError);
    }

    private void notify(String method, Map<String, Object> params) throws IOException {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        msg.put("params", params);
        send(msg);
    }

    private void send(Map<String, Object> msg) throws IOException {
        if (stdin == null) {
            throw new IOException("MCP server not connected");
        }
        stdin.write(Json.stringify(msg));
        stdin.write("\n");
        stdin.flush();
    }
}
