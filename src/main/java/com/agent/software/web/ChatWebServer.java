package com.agent.software.web;

import com.agent.software.AgentSystem;
import com.agent.software.io.WebInput;
import com.agent.software.role.Role;
import com.agent.software.utils.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零依赖的 Web UI/接口：消息流、客户回复、客户主动对话、角色列表。
 *
 * <p>只依赖 JDK 的 {@code com.sun.net.httpserver}。
 */
public class ChatWebServer {

    private static final Logger logger = LoggerFactory.getLogger(ChatWebServer.class);

    private final AgentSystem system;
    private final String host;
    private final int requestedPort;
    private HttpServer server;

    public ChatWebServer(AgentSystem system, String host, int port) throws IOException {
        this.system = system;
        this.host = host == null || host.isBlank() ? "127.0.0.1" : host;
        this.requestedPort = port;
    }

    public void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(host, requestedPort), 0);
            server.createContext("/", this::handleStatic);
            server.createContext("/api/messages", this::handleMessages);
            server.createContext("/api/reply", this::handleReply);
            server.createContext("/api/talk", this::handleTalk);
            server.createContext("/api/roles", this::handleRoles);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            logger.info("ChatWebServer started: http://{}:{}/", host, port());
        } catch (IOException e) {
            throw new RuntimeException("cannot start web server", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            logger.info("ChatWebServer stopped");
        }
    }

    public int port() {
        return server == null ? requestedPort : server.getAddress().getPort();
    }

    public String host() {
        return host;
    }

    // ── handlers ────────────────────────────────────────────────

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) {
            path = "/web/index.html";
        } else if (!path.startsWith("/web/")) {
            path = "/web" + path;
        }
        try (InputStream in = ChatWebServer.class.getResourceAsStream(path)) {
            if (in == null) {
                respond(ex, 404, "text/plain", "not found: " + path);
                return;
            }
            byte[] body = in.readAllBytes();
            respond(ex, 200, contentType(path), new String(body, StandardCharsets.UTF_8));
        }
    }

    private void handleMessages(HttpExchange ex) throws IOException {
        long since = queryLong(ex, "since", 0L);
        List<Map<String, Object>> messages = system.getChatStore().messagesSince(since);
        respond(ex, 200, "application/json", Json.stringify(Map.of("messages", messages)));
    }

    private void handleReply(HttpExchange ex) throws IOException {
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        system.getChatStore().postClientReply(text);
        if (system.getClientChannel() != null) {
            system.getClientChannel().reply(text);
        }
        respond(ex, 200, "application/json", Json.stringify(Map.of("ok", true)));
    }

    private void handleTalk(HttpExchange ex) throws IOException {
        String roleId = query(ex, "role");
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String result;
        if (system.getClientChannel() == null) {
            result = "no client channel";
        } else {
            result = system.getClientChannel().talk(roleId, text, false);
        }
        respond(ex, 200, "application/json", Json.stringify(Map.of("result", result)));
    }

    private void handleRoles(HttpExchange ex) throws IOException {
        List<Map<String, Object>> roles = new ArrayList<>();
        for (Role r : system.getRolePool().all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role_id", r.roleId);
            m.put("name", r.name);
            m.put("group", r.group);
            m.put("state", r.getState().name());
            roles.add(m);
        }
        respond(ex, 200, "application/json", Json.stringify(Map.of("roles", roles)));
    }

    // ── helpers ─────────────────────────────────────────────────

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private static void respond(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) {
            return "";
        }
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(key)) {
                return java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static long queryLong(HttpExchange ex, String key, long def) {
        String v = query(ex, key);
        if (v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
