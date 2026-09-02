package com.agent.software.web;

import com.agent.software.AgentSystem;
import com.agent.software.role.AgentRole;
import com.agent.software.role.RoleLoader;
import com.agent.software.tools.Toolkits;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Web UI server (JDK built-in HttpServer, zero extra dependencies):
 *
 * <ul>
 *   <li>Static resources: classpath {@code /web/*} (index.html / style.css / app.js);</li>
 *   <li>{@code GET /api/state}   — group roster + system time + client dialogue state;</li>
 *   <li>{@code GET /api/messages?since=N} — new messages with seq &gt; N;</li>
 *   <li>{@code POST /api/reply}  — the client submits a reply {@code {"text": "..."}};</li>
 *   <li>{@code POST /api/attach} — Web frontend attach heartbeat (any API poll also refreshes the heartbeat).</li>
 * </ul>
 *
 * <p>Bind address/port/client-reply timeout are configured via environment variables or system
 * properties ({@code AGENTCOMPANY_WEB_HOST} / {@code AGENTCOMPANY_WEB_PORT} /
 * {@code AGENTCOMPANY_CLIENT_REPLY_TIMEOUT}), following the same convention as PathManager.
 */
public final class ChatWebServer {

    private static final Logger logger = LoggerFactory.getLogger(ChatWebServer.class);

    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_PORT = 8787;
    /** Default client-reply timeout is 20 minutes (Web mode). */
    public static final long DEFAULT_REPLY_TIMEOUT_MS = 20 * 60 * 1000L;

    private static final Map<String, String> MIME = new LinkedHashMap<>();

    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("js", "application/javascript; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("svg", "image/svg+xml");
        MIME.put("png", "image/png");
        MIME.put("ico", "image/x-icon");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("txt", "text/plain; charset=utf-8");
    }

    /** Group English name → display label (shown in the Web UI). */
    private static final Map<String, String> GROUP_LABELS = new LinkedHashMap<>();

    static {
        GROUP_LABELS.put("Leadership Group", "Leadership Group");
        GROUP_LABELS.put("Frontend Development Group", "Frontend Development Group");
        GROUP_LABELS.put("Backend Development Group", "Backend Development Group");
        GROUP_LABELS.put("Mobile Development Group", "Mobile Development Group");
        GROUP_LABELS.put("Full-Stack Development Group", "Full-Stack Development Group");
        GROUP_LABELS.put("Testing Group", "Testing Group");
        GROUP_LABELS.put("Security Group", "Security Group");
        GROUP_LABELS.put("Architecture & Release Group", "Architecture & Release Group");
        GROUP_LABELS.put("Operations Group", "Operations Group");
        GROUP_LABELS.put("Marketing Group", "Marketing Group");
        GROUP_LABELS.put("Data Group", "Data Group");
        GROUP_LABELS.put("Support Group", "Support Group");
        GROUP_LABELS.put("", "Unassigned");
    }

    /** Config reading: environment variable first, falling back to system property (same convention as PathManager). */
    private static String config(String key, String def) {
        String v = System.getenv(key);
        if (v != null && !v.isEmpty()) {
            return v;
        }
        String p = System.getProperty(key, "");
        return p == null || p.isEmpty() ? def : p;
    }

    /** Bind address (default 0.0.0.0). */
    public static String configuredHost() {
        return config("AGENTCOMPANY_WEB_HOST", DEFAULT_HOST);
    }

    /** Listening port (default 8787; 0 = random port, for tests). */
    public static int configuredPort() {
        try {
            return Integer.parseInt(config("AGENTCOMPANY_WEB_PORT", String.valueOf(DEFAULT_PORT)));
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    /** Client reply timeout in milliseconds. */
    public static long replyTimeoutMs() {
        try {
            return Long.parseLong(config("AGENTCOMPANY_CLIENT_REPLY_TIMEOUT",
                    String.valueOf(DEFAULT_REPLY_TIMEOUT_MS)));
        } catch (NumberFormatException e) {
            return DEFAULT_REPLY_TIMEOUT_MS;
        }
    }

    private final AgentSystem system;
    private final ChatStore store;
    private final HttpServer server;
    private final String host;
    private final int port;

    /** Created with default config (host/port via environment variable or system property). */
    public ChatWebServer(AgentSystem system) throws IOException {
        this(system, configuredHost(), configuredPort());
    }

    /** Created with explicit host/port (port=0 uses a random port, convenient for tests). */
    public ChatWebServer(AgentSystem system, String host, int port) throws IOException {
        this.system = system;
        this.store = system.chatStore;  // each AgentSystem holds its own ChatStore
        this.host = host;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.port = server.getAddress().getPort();
        server.createContext("/", this::handle);
        // A default single-thread executor is enough: the API is very lightweight and static resources are fast to read
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Starts the server (non-blocking). */
    public void start() {
        server.start();
        logger.info("ChatWebServer started: http://{}:{}/ (group chat + client dialogue Web UI)",
                host, port);
    }

    /** Stops the server. */
    public void stop() {
        server.stop(0);
        logger.info("ChatWebServer stopped");
    }

    /** Actual listening port (returns the real randomly assigned port when configured with 0). */
    public int port() {
        return port;
    }

    public String host() {
        return host;
    }

    // ── HTTP handling ───────────────────────────────────────────

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                handleApi(ex, path);
            } else {
                handleStatic(ex, path);
            }
        } catch (Exception e) {
            logger.error("ChatWebServer: request handling failed {} {}",
                    ex.getRequestMethod(), ex.getRequestURI(), e);
            try {
                sendJson(ex, 500, Map.of("ok", false, "reason", "internal error: " + e.getMessage()));
            } catch (IOException io) {
                logger.warn("ChatWebServer: failed to send 500 response", io);
            }
        } finally {
            ex.close();
        }
    }

    private void handleApi(HttpExchange ex, String path) throws IOException {
        if (store != null) {
            store.markAttached();  // any API poll refreshes the Web attach heartbeat
        }
        switch (path) {
            case "/api/state" -> sendJson(ex, 200, apiState());
            case "/api/messages" -> sendJson(ex, 200, apiMessages(ex));
            case "/api/reply" -> handleReply(ex);
            case "/api/attach" -> sendJson(ex, 200, Map.of("ok", true, "attached", true));
            default -> sendJson(ex, 404, Map.of("ok", false, "reason", "unknown api: " + path));
        }
    }

    // ── /api/state ──────────────────────────────────────────

    private Map<String, Object> apiState() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("day", system.day());
        resp.put("tick", system.tick());
        resp.put("tickOfDay", system.timeManager.tickOfDay());
        resp.put("describe", system.describe());

        Map<String, Object> web = new LinkedHashMap<>();
        web.put("host", host);
        web.put("port", port);
        web.put("url", "http://127.0.0.1:" + port + "/");
        web.put("attached", store != null && store.isAttached());
        resp.put("web", web);

        resp.put("groups", buildGroups());

        Map<String, Object> clientTalk = new LinkedHashMap<>();
        boolean active = store != null && store.isClientWaitPending();
        clientTalk.put("active", active);
        clientTalk.put("holderName", active && store != null ? store.pendingHolderName() : null);
        clientTalk.put("holderRoleId", active && store != null ? store.pendingHolderRoleId() : null);
        resp.put("clientTalk", clientTalk);
        return resp;
    }

    /** Group roster: current role pool (active members) + role templates (not yet hired but in the group) merged; leadership group first. */
    private List<Map<String, Object>> buildGroups() {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        Set<String> seenRoleIds = new HashSet<>();
        // 1) Current role pool (active members, including dynamically hired newcomers)
        if (system.pool != null) {
            for (AgentRole r : system.pool.allRoles()) {
                addMember(groups, seenRoleIds, r);
            }
        }
        // 2) Template completion: members not hired but belonging to a group (full roster)
        for (Map.Entry<String, Supplier<AgentRole>> e : RoleLoader.TEMPLATES.entrySet()) {
            if (!seenRoleIds.contains(e.getKey())) {
                addMember(groups, seenRoleIds, e.getValue().get());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : groups.entrySet()) {
            Map<String, Object> g = e.getValue();
            g.put("key", e.getKey());
            g.put("label", labelFor(e.getKey()));
            out.add(g);
        }
        // Leadership group first, the rest sorted by group name
        out.sort(Comparator
                .comparingInt((Map<String, Object> g) -> Toolkits.LEADERSHIP_GROUP.equals(g.get("key")) ? 0 : 1)
                .thenComparing(g -> (String) g.get("key")));
        return out;
    }

    @SuppressWarnings("unchecked")
    private void addMember(Map<String, Map<String, Object>> groups, Set<String> seenRoleIds, AgentRole r) {
        String key = r.group == null || r.group.isBlank() ? "" : r.group;
        Map<String, Object> g = groups.computeIfAbsent(key, k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("members", new ArrayList<Map<String, Object>>());
            return m;
        });
        if (!seenRoleIds.add(r.roleId)) {
            return;
        }
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("roleId", r.roleId);
        member.put("name", r.name);
        member.put("title", r.title == null ? "" : r.title);
        ((List<Map<String, Object>>) g.get("members")).add(member);
    }

    private static String labelFor(String groupKey) {
        return GROUP_LABELS.getOrDefault(groupKey, groupKey.isEmpty() ? "Unassigned" : groupKey);
    }

    // ── /api/messages ───────────────────────────────────────

    private Map<String, Object> apiMessages(HttpExchange ex) {
        long since = 0;
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] parts = kv.split("=", 2);
                if (parts.length == 2 && "since".equals(parts[0])) {
                    try {
                        since = Long.parseLong(parts[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("lastSeq", store != null ? store.lastSeq() : 0);
        resp.put("messages", store != null ? store.messagesSince(since) : List.of());
        return resp;
    }

    // ── POST /api/reply ─────────────────────────────────────

    private void handleReply(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("ok", false, "reason", "method not allowed"));
            return;
        }
        if (store == null) {
            sendJson(ex, 500, Map.of("ok", false, "reason", "chat store unavailable"));
            return;
        }
        String bodyText = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String reply = "";
        try {
            Map<String, Object> body = Json.parseObject(bodyText);
            reply = Json.str(body, "text", "").strip();
        } catch (Exception e) {
            sendJson(ex, 400, Map.of("ok", false, "reason", "invalid json body"));
            return;
        }
        if (reply.isEmpty()) {
            sendJson(ex, 400, Map.of("ok", false, "reason", "reply text must not be empty"));
            return;
        }
        ChatStore.ChatMessage recorded = store.postClientReply(reply);
        if (recorded == null) {
            sendJson(ex, 409, Map.of("ok", false, "reason", "no client dialogue is currently pending"));
            return;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("delivered", true);
        resp.put("message", ChatStore.toMap(recorded));
        sendJson(ex, 200, resp);
    }

    // ── Static resources ────────────────────────────────────

    private void handleStatic(HttpExchange ex, String path) throws IOException {
        String name = path.equals("/") ? "index.html" : path.substring(1);
        if (name.contains("..") || name.contains("\\")) {
            sendJson(ex, 404, Map.of("ok", false, "reason", "not found"));
            return;
        }
        byte[] content = readResource("web/" + name);
        if (content == null) {
            sendJson(ex, 404, Map.of("ok", false, "reason", "not found: " + name));
            return;
        }
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "html";
        String mime = MIME.getOrDefault(ext, "application/octet-stream");
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.sendResponseHeaders(200, content.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(content);
        }
    }

    private static byte[] readResource(String path) {
        try (InputStream in = ChatWebServer.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    // ── Response helpers ────────────────────────────────────

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
