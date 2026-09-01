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
 * Web 界面服务器 (JDK 内置 HttpServer, 零新增依赖):
 *
 * <ul>
 *   <li>静态资源: classpath {@code /web/*} (index.html / style.css / app.js);</li>
 *   <li>{@code GET /api/state}   — 分组花名册 + 系统时间 + 甲方对话状态;</li>
 *   <li>{@code GET /api/messages?since=N} — seq 大于 N 的新消息;</li>
 *   <li>{@code POST /api/reply}  — 甲方提交回复 {@code {"text": "..."}};</li>
 *   <li>{@code POST /api/attach} — Web 前端挂载心跳 (任意 API 轮询也会刷新心跳).</li>
 * </ul>
 *
 * <p>绑定地址/端口/甲方回复超时通过环境变量或系统属性配置
 * ({@code AGENTCOMPANY_WEB_HOST} / {@code AGENTCOMPANY_WEB_PORT} /
 * {@code AGENTCOMPANY_CLIENT_REPLY_TIMEOUT}), 约定与 PathManager 一致.
 */
public final class ChatWebServer {

    private static final Logger logger = LoggerFactory.getLogger(ChatWebServer.class);

    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_PORT = 8787;
    /** 甲方回复超时默认 20 分钟 (Web 模式). */
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

    /** 分组英文名 → 中文标签 (Web 界面展示). */
    private static final Map<String, String> GROUP_LABELS = new LinkedHashMap<>();

    static {
        GROUP_LABELS.put("Leadership Group", "领导组");
        GROUP_LABELS.put("Frontend Development Group", "前端开发组");
        GROUP_LABELS.put("Backend Development Group", "后端开发组");
        GROUP_LABELS.put("Mobile Development Group", "移动开发组");
        GROUP_LABELS.put("Full-Stack Development Group", "全栈开发组");
        GROUP_LABELS.put("Testing Group", "测试组");
        GROUP_LABELS.put("Security Group", "安全组");
        GROUP_LABELS.put("Architecture & Release Group", "架构与发布组");
        GROUP_LABELS.put("Operations Group", "运维组");
        GROUP_LABELS.put("Marketing Group", "市场组");
        GROUP_LABELS.put("Data Group", "数据组");
        GROUP_LABELS.put("Support Group", "客服组");
        GROUP_LABELS.put("", "未分组");
    }

    /** 配置读取: 环境变量优先, 回退系统属性 (与 PathManager 约定一致). */
    private static String config(String key, String def) {
        String v = System.getenv(key);
        if (v != null && !v.isEmpty()) {
            return v;
        }
        String p = System.getProperty(key, "");
        return p == null || p.isEmpty() ? def : p;
    }

    /** 绑定地址 (默认 0.0.0.0). */
    public static String configuredHost() {
        return config("AGENTCOMPANY_WEB_HOST", DEFAULT_HOST);
    }

    /** 监听端口 (默认 8787; 0 = 随机端口, 测试用). */
    public static int configuredPort() {
        try {
            return Integer.parseInt(config("AGENTCOMPANY_WEB_PORT", String.valueOf(DEFAULT_PORT)));
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    /** 甲方回复超时毫秒数. */
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

    /** 默认配置创建 (host/port 走环境变量或系统属性). */
    public ChatWebServer(AgentSystem system) throws IOException {
        this(system, configuredHost(), configuredPort());
    }

    /** 显式 host/port 创建 (port=0 时使用随机端口, 便于测试). */
    public ChatWebServer(AgentSystem system, String host, int port) throws IOException {
        this.system = system;
        this.store = system.context() != null ? system.context().chatStore : null;
        this.host = host;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.port = server.getAddress().getPort();
        server.createContext("/", this::handle);
        // 默认单线程 executor 即可: API 极轻量, 静态资源读取也很快
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    /** 启动服务器 (非阻塞). */
    public void start() {
        server.start();
        logger.info("ChatWebServer 已启动: http://{}:{}/ (分组聊天 + 甲方对话 Web 界面)",
                host, port);
    }

    /** 停止服务器. */
    public void stop() {
        server.stop(0);
        logger.info("ChatWebServer 已停止");
    }

    /** 实际监听端口 (配置 0 时返回随机分配的真实端口). */
    public int port() {
        return port;
    }

    public String host() {
        return host;
    }

    // ── HTTP 处理 ───────────────────────────────────────────

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                handleApi(ex, path);
            } else {
                handleStatic(ex, path);
            }
        } catch (Exception e) {
            logger.error("ChatWebServer: 请求处理失败 {} {}", ex.getRequestMethod(),
                    ex.getRequestURI(), e);
            try {
                sendJson(ex, 500, Map.of("ok", false, "reason", "internal error: " + e.getMessage()));
            } catch (IOException io) {
                logger.warn("ChatWebServer: 发送 500 响应失败", io);
            }
        } finally {
            ex.close();
        }
    }

    private void handleApi(HttpExchange ex, String path) throws IOException {
        if (store != null) {
            store.markAttached();  // 任意 API 轮询都刷新 Web 挂载心跳
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

    /** 分组花名册: 当前角色池 (在职) + 角色模板 (未入职但属于该组) 合并, 领导组排最前. */
    private List<Map<String, Object>> buildGroups() {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        Set<String> seenRoleIds = new HashSet<>();
        // 1) 当前角色池 (在职成员, 含动态入职新人)
        if (system.pool != null) {
            for (AgentRole r : system.pool.allRoles()) {
                addMember(groups, seenRoleIds, r);
            }
        }
        // 2) 模板补全: 未入职但属于某组的成员 (完整花名册)
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
        // 领导组排最前, 其余按组名排序
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
        return GROUP_LABELS.getOrDefault(groupKey, groupKey.isEmpty() ? "未分组" : groupKey);
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
            sendJson(ex, 400, Map.of("ok", false, "reason", "回复内容不能为空"));
            return;
        }
        ChatStore.ChatMessage recorded = store.postClientReply(reply);
        if (recorded == null) {
            sendJson(ex, 409, Map.of("ok", false, "reason", "当前没有等待中的甲方对话"));
            return;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("delivered", true);
        resp.put("message", ChatStore.toMap(recorded));
        sendJson(ex, 200, resp);
    }

    // ── 静态资源 ────────────────────────────────────────────

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

    // ── 响应工具 ────────────────────────────────────────────

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
