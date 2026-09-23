package com.agent.software.web;

import com.agent.software.AgentSystem;
import com.agent.software.client.ClientChannel;
import com.agent.software.event.TimeBus;
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
 * 零依赖的 Web UI/接口（JDK {@code com.sun.net.httpserver}）。
 *
 * <p>接口契约对齐 `src/main/resources/web/app.js`（master 遗留的静态前端）：
 * <ul>
 *   <li>{@code GET  /api/state}  → {@code {ok, day, tick, date, time, describe, paused, pauseReason, clientTalk, groups}}</li>
 *   <li>{@code GET  /api/messages?since=N} → {@code {ok, lastSeq, messages:[camelCase…]}}</li>
 *   <li>{@code POST /api/reply}  body {@code {text}} → {@code {ok, message}}</li>
 *   <li>{@code POST /api/pause} / {@code POST /api/resume} → {@code {ok}}</li>
 *   <li>{@code POST /api/talk?role=ID} / {@code GET /api/roles}（额外保留）</li>
 * </ul>
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
            server.createContext("/api/state", this::handleState);
            server.createContext("/api/messages", this::handleMessages);
            server.createContext("/api/reply", this::handleReply);
            server.createContext("/api/pause", this::handlePause);
            server.createContext("/api/resume", this::handleResume);
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

    /** 顶部状态栏 + 侧栏分组 + 客户对话状态 + 暂停状态。 */
    private void handleState(HttpExchange ex) throws IOException {
        TimeBus tb = system.getTimeBus();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("day", tb.getDay());
        body.put("tick", tb.now());
        body.put("date", tb.currentDate().toString());
        body.put("time", tb.currentTime());
        body.put("describe", "shift " + tb.getShiftStartTick() + "–" + tb.getShiftEndTick()
                + " · working=" + tb.isWorkingHours());
        body.put("paused", tb.isPaused());
        body.put("pauseReason", "");

        ClientChannel channel = system.getClientChannel();
        String holderRoleId = channel == null ? null : channel.getCurrentRoleId();
        Role holder = holderRoleId == null ? null : system.getRolePool().find(holderRoleId);
        Map<String, Object> clientTalk = new LinkedHashMap<>();
        clientTalk.put("active", holderRoleId != null);
        clientTalk.put("holderRoleId", holderRoleId == null ? "" : holderRoleId);
        clientTalk.put("holderName", holder == null ? "" : holder.name);
        clientTalk.put("group", holder == null ? "" : holder.group);
        body.put("clientTalk", clientTalk);

        Map<String, List<Map<String, Object>>> byGroup = new LinkedHashMap<>();
        for (Role r : system.getRolePool().all()) {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("name", r.name);
            member.put("roleId", r.roleId);
            member.put("state", r.getState().name());
            byGroup.computeIfAbsent(r.group == null ? "" : r.group, k -> new ArrayList<>()).add(member);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byGroup.entrySet()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("key", e.getKey());
            g.put("name", e.getKey());
            g.put("members", e.getValue());
            groups.add(g);
        }
        body.put("groups", groups);

        respond(ex, 200, "application/json", Json.stringify(body));
    }

    private void handleMessages(HttpExchange ex) throws IOException {
        long since = queryLong(ex, "since", 0L);
        ChatStore store = system.getChatStore();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("lastSeq", store.lastSeq());
        body.put("messages", store.messagesSince(since));
        respond(ex, 200, "application/json", Json.stringify(body));
    }

    private void handleReply(HttpExchange ex) throws IOException {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String text = extractText(raw);
        ChatStore.ChatMessage message = system.getChatStore().postClientReply(text);
        if (system.getClientChannel() != null) {
            system.getClientChannel().reply(text);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("message", ChatStore.toMap(message));
        respond(ex, 200, "application/json", Json.stringify(body));
    }

    private void handlePause(HttpExchange ex) throws IOException {
        system.pause();
        respond(ex, 200, "application/json", Json.stringify(Map.of("ok", true)));
    }

    private void handleResume(HttpExchange ex) throws IOException {
        system.resume();
        respond(ex, 200, "application/json", Json.stringify(Map.of("ok", true)));
    }

    private void handleTalk(HttpExchange ex) throws IOException {
        String roleId = query(ex, "role");
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String text = extractText(raw);
        String result;
        if (system.getClientChannel() == null) {
            result = "no client channel";
        } else {
            result = system.getClientChannel().talk(roleId, text, false);
        }
        respond(ex, 200, "application/json", Json.stringify(Map.of("ok", true, "result", result)));
    }

    private void handleRoles(HttpExchange ex) throws IOException {
        List<Map<String, Object>> roles = new ArrayList<>();
        for (Role r : system.getRolePool().all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roleId", r.roleId);
            m.put("name", r.name);
            m.put("group", r.group);
            m.put("state", r.getState().name());
            roles.add(m);
        }
        respond(ex, 200, "application/json", Json.stringify(Map.of("ok", true, "roles", roles)));
    }

    // ── helpers ─────────────────────────────────────────────────

    /** 前端发的是 {@code {"text":"..."}}，也兼容纯文本。 */
    private static String extractText(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.stripLeading();
        if (trimmed.startsWith("{")) {
            try {
                Object t = Json.parseObject(raw).get("text");
                if (t != null) {
                    return String.valueOf(t);
                }
            } catch (Exception ignored) {
            }
        }
        return raw;
    }

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
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
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
