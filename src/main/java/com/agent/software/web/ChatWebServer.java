package com.agent.software.web;

import com.agent.software.agent.AgentSnapshot;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.company.CompanyStatus;
import com.agent.software.company.CompanyView;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.infra.json.JsonCodec;
import com.agent.software.kernel.DayTick;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Text;
import com.agent.software.transcript.ChatFeed;
import com.agent.software.transcript.Transcript.Entry;
import com.agent.software.transcript.Transcript.Feed;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * JDK HttpServer，路由 /api/state /api/messages /api/reply /api/pause /api/resume /api/attach + 静态资源。
 *
 * <p>只依赖 {@link CompanyView}（只读快照 + 暂停开关）与 {@link Feed}（轨迹增量拉取），
 * 不再像 master 那样直接抓 {@code AgentSystem}/{@code RolePool} 内部。
 *
 * <p>JSON 字段名与 master 的 {@code /api/state}、{@code /api/messages} 保持一致
 * （前端 {@code app.js} 直接消费 camelCase 字段）；消息 kind 见 {@link ChatFeed} 的常量。
 */
public final class ChatWebServer {

    private static final Logger logger = LoggerFactory.getLogger(ChatWebServer.class);

    /** 静态资源 MIME 表（对齐 master）。 */
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

    /** 前端把组长组排在最前并据此自动切频道（app.js 里的 LEADERSHIP_KEY）。 */
    private static final String LEADERSHIP_GROUP = "Leadership Group";

    /** 请求体上限 1MB：避免异常客户端把内存打满。 */
    private static final int MAX_BODY_BYTES = 1 << 20;

    private static final JsonCodec JSON = new JacksonJsonCodec();

    private final CompanyView view;
    private final Feed feed;
    private final HttpServer server;
    /** 实际监听地址/端口（port=0 时是系统分配值）。 */
    private final String host;
    private final int port;

    /** 绑定公司只读视图、轨迹 feed 与监听地址。 */
    public ChatWebServer(CompanyView view, Feed feed, String host, int port) {
        this.view = view;
        this.feed = feed;
        String bindHost = Text.isBlank(host) ? "0.0.0.0" : host;
        try {
            this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        } catch (IOException e) {
            throw new DomainError("web.bind.failed",
                    "Web 服务绑定 " + bindHost + ":" + port + " 失败", e);
        }
        this.host = server.getAddress().getHostString();
        this.port = server.getAddress().getPort();
        server.createContext("/", this::handle);
        // API 很轻、静态资源也小：虚拟线程执行器足够，且不会因长轮询/阻塞拖垮线程池
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        bindRoleResolver();
    }

    /**
     * 轨迹写入只带 {@code RoleId}，而前端要用 {@code fromName}/{@code group} 分频道渲染。
     * 若调用方没有在构造 {@link ChatFeed} 时注入解析器，这里用 {@link CompanyView#roster()}
     * 兜底绑定一次，保证 Web 模式下轨迹能落到正确的组与发言人。
     */
    private void bindRoleResolver() {
        if (view == null || !(feed instanceof ChatFeed chatFeed)) {
            return;
        }
        chatFeed.bindResolver(id -> {
            for (RoleSpec spec : view.roster()) {
                if (spec != null && spec.id() != null && spec.id().equals(id)) {
                    return new ChatFeed.RoleInfo(spec.name(), spec.group());
                }
            }
            return null;
        });
    }

    /** 启动 HTTP 服务。 */
    public void start() {
        server.start();
        logger.info("ChatWebServer started: http://{}:{}/", host, port);
    }

    /** 停止 HTTP 服务。 */
    public void stop() {
        server.stop(0);
        logger.info("ChatWebServer stopped");
    }

    /** 实际监听端口（port=0 时为系统分配的端口）。 */
    public int port() {
        return port;
    }

    /** 实际监听地址。 */
    public String host() {
        return host;
    }

    // ── 路由 ────────────────────────────────────────────────────

    /** 所有请求的总入口：任何异常都转 500，绝不让请求线程抛出。 */
    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            if (path != null && path.startsWith("/api/")) {
                handleApi(ex, path);
            } else {
                handleStatic(ex, path == null ? "/" : path);
            }
        } catch (Exception e) {
            logger.error("ChatWebServer: 请求处理失败 {} {}", ex.getRequestMethod(),
                    ex.getRequestURI(), e);
            trySend(ex, 500, error("internal error: " + e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private void handleApi(HttpExchange ex, String path) throws IOException {
        touchAttach(); // 任何 API 轮询都刷新浏览器心跳
        switch (path) {
            case "/api/state" -> sendJson(ex, 200, apiState());
            case "/api/messages" -> sendJson(ex, 200, apiMessages(ex));
            case "/api/reply" -> handleReply(ex);
            case "/api/email" -> handleEmail(ex);
            case "/api/pause" -> handlePause(ex, true);
            case "/api/resume" -> handlePause(ex, false);
            case "/api/attach" -> {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("ok", true);
                resp.put("attached", true);
                sendJson(ex, 200, resp);
            }
            default -> sendJson(ex, 404, error("unknown api: " + path));
        }
    }

    // ── /api/state ──────────────────────────────────────────────

    private Map<String, Object> apiState() {
        CompanyStatus status = view == null ? null : view.status();
        DayTick clock = status == null ? null : status.clock();
        String dateTime = status == null ? "" : Text.orEmpty(status.dateTime());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("day", clock == null ? 0 : clock.day());
        // master 的 tick 是自开局起的绝对 tick；CompanyStatus 只暴露 DayTick，无法还原绝对 tick。
        // 该字段前端并不使用，这里保留字段名、值为 null，避免下游按字段名取值时拿到 undefined。
        resp.put("tick", null);
        resp.put("tickOfDay", clock == null ? 0 : clock.tickOfDay());
        resp.put("date", dateTime.length() >= 10 ? dateTime.substring(0, 10) : "");
        resp.put("time", clockTime(dateTime));
        resp.put("datetime", dateTime);
        resp.put("describe", status == null ? "" : Text.orEmpty(status.describe()));
        boolean paused = status != null && status.paused();
        resp.put("paused", paused);
        resp.put("pauseReason", paused ? Text.orEmpty(status.pauseReason()) : "");
        resp.put("watermark", feed == null ? 0L : feed.watermark());

        Map<String, Object> web = new LinkedHashMap<>();
        web.put("host", host);
        web.put("port", port);
        web.put("url", "http://127.0.0.1:" + port + "/");
        web.put("attached", feed instanceof ChatFeed cf && cf.clientAttached());
        resp.put("web", web);

        resp.put("groups", buildGroups(status));
        resp.put("clientTalk", buildClientTalk());
        return resp;
    }

    /** 组名 + 显示标签 + 成员（roleId/name/title/group/state/busy/queueDepth/currentTask）。 */
    private List<Map<String, Object>> buildGroups(CompanyStatus status) {
        Map<String, AgentSnapshot> byId = new HashMap<>();
        if (status != null && status.agents() != null) {
            for (AgentSnapshot snap : status.agents()) {
                if (snap != null && snap.id() != null) {
                    byId.put(snap.id().value(), snap);
                }
            }
        }
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        if (view != null && view.roster() != null) {
            for (RoleSpec spec : view.roster()) {
                if (spec == null || spec.id() == null) {
                    continue;
                }
                String key = Text.isBlank(spec.group()) ? "" : spec.group();
                Map<String, Object> group = groups.computeIfAbsent(key, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("members", new ArrayList<Map<String, Object>>());
                    return m;
                });
                AgentSnapshot snap = byId.get(spec.id().value());
                Map<String, Object> member = new LinkedHashMap<>();
                member.put("roleId", spec.id().value());
                member.put("name", Text.orEmpty(spec.name()));
                member.put("title", Text.orEmpty(spec.title()));
                member.put("group", key);
                member.put("state", snap == null || snap.state() == null ? "" : snap.state().name());
                member.put("busy", snap != null && snap.busy());
                member.put("queueDepth", snap == null ? 0 : snap.queueDepth());
                member.put("currentTask", snap == null ? "" : Text.orEmpty(snap.currentTask()));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> members = (List<Map<String, Object>>) group.get("members");
                members.add(member);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : groups.entrySet()) {
            Map<String, Object> group = e.getValue();
            group.put("key", e.getKey());
            group.put("label", labelFor(e.getKey()));
            out.add(group);
        }
        // 组长组固定排第一，其余按组名排序（对齐 master，前端默认聚焦 Leadership Group）
        out.sort(Comparator
                .comparingInt((Map<String, Object> g) -> LEADERSHIP_GROUP.equals(g.get("key")) ? 0 : 1)
                .thenComparing(g -> String.valueOf(g.get("key"))));
        return out;
    }

    private static String labelFor(String groupKey) {
        return Text.isBlank(groupKey) ? "Unassigned" : groupKey;
    }

    /** 客户对话状态：只有 Web 通道（ChatFeed）才有会合点。 */
    private Map<String, Object> buildClientTalk() {
        Map<String, Object> clientTalk = new LinkedHashMap<>();
        if (feed instanceof ChatFeed chatFeed) {
            ChatFeed.ClientDialogue dialogue = chatFeed.clientDialogue();
            boolean active = dialogue.waitingRoleId() != null;
            clientTalk.put("active", active);
            clientTalk.put("holderName", active ? dialogue.waitingName() : null);
            clientTalk.put("holderRoleId", active ? dialogue.waitingRoleId() : null);
            clientTalk.put("attached", dialogue.attached());
            clientTalk.put("pendingQuestion", Text.orEmpty(dialogue.pendingQuestion()));
        } else {
            clientTalk.put("active", false);
            clientTalk.put("holderName", null);
            clientTalk.put("holderRoleId", null);
            clientTalk.put("attached", false);
            clientTalk.put("pendingQuestion", "");
        }
        return clientTalk;
    }

    /** "yyyy-MM-dd HH:mm:ss" → "HH:mm:ss"（拿不到合法格式时退化为空串）。 */
    private static String clockTime(String dateTime) {
        if (dateTime == null || dateTime.length() < 19 || dateTime.charAt(10) != ' ') {
            return "";
        }
        return dateTime.substring(11, 19);
    }

    // ── /api/messages ───────────────────────────────────────────

    private Map<String, Object> apiMessages(HttpExchange ex) {
        long since = 0L;
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] parts = kv.split("=", 2);
                // since 非数字按 0 处理（master 行为）
                if (parts.length == 2 && "since".equals(parts[0])) {
                    try {
                        since = Long.parseLong(parts[1]);
                    } catch (NumberFormatException ignored) {
                        since = 0L;
                    }
                }
            }
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        long watermark = 0L;
        if (feed != null) {
            watermark = feed.watermark();
            for (Entry entry : feed.since(since)) {
                messages.add(entryToMap(entry));
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        // lastSeq 是 master 的字段名（前端读 body.lastSeq）；watermark 是新架构的叫法，两个都给
        resp.put("lastSeq", watermark);
        resp.put("watermark", watermark);
        resp.put("messages", messages);
        return resp;
    }

    /** Entry → 前端消费的 JSON Map（字段名照 master 的 ChatStore.toMap）。 */
    private static Map<String, Object> entryToMap(Entry entry) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seq", entry.seq());
        out.put("ts", entry.timestamp());
        out.put("kind", entry.kind());
        out.put("group", entry.group());
        out.put("fromRoleId", entry.fromRoleId());
        out.put("fromName", entry.fromName());
        out.put("toRoleId", entry.toRoleId());
        out.put("toName", entry.toName());
        out.put("text", entry.text());
        Map<String, Object> extra = entry.extra() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(entry.extra().asMap());
        // talk 的 urgency 在 Entry 里只能放 extra，序列化时提到顶层供前端渲染加急徽标
        Object urgency = extra.remove("urgency");
        if (urgency != null && !String.valueOf(urgency).isBlank()) {
            out.put("urgency", urgency);
        }
        if (!extra.isEmpty()) {
            out.put("extra", extra);
        }
        return out;
    }

    // ── POST /api/reply ─────────────────────────────────────────

    private void handleReply(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, error("method not allowed"));
            return;
        }
        if (!(feed instanceof ChatFeed chatFeed)) {
            sendJson(ex, 409, error("the transcript feed does not support client replies"));
            return;
        }
        String bodyText = readBody(ex);
        if (bodyText == null) {
            sendJson(ex, 413, error("request body too large"));
            return;
        }
        String reply;
        if (bodyText.isBlank()) {
            reply = "";
        } else {
            try {
                Map<String, Object> body = JSON.readMap(bodyText);
                Object text = body.get("text");
                reply = text == null ? "" : String.valueOf(text).strip();
            } catch (RuntimeException e) {
                sendJson(ex, 400, error("invalid json body"));
                return;
            }
        }
        if (reply.isEmpty()) {
            sendJson(ex, 400, error("reply text must not be empty"));
            return;
        }
        long before = chatFeed.watermark();
        boolean delivered = chatFeed.submitClientReply(reply);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("delivered", delivered);
        // 前端拿到 message 会立刻渲染客户气泡（并写 lastSeq，避免重复拉取）
        Map<String, Object> message = lastClientReply(chatFeed, before);
        if (message != null) {
            resp.put("message", message);
        }
        sendJson(ex, 200, resp);
    }

    /** 取本次提交写入的那条客户回复条目（无等待者时也会写入）。 */
    private static Map<String, Object> lastClientReply(ChatFeed feed, long sinceSeq) {
        Map<String, Object> found = null;
        for (Entry entry : feed.since(sinceSeq)) {
            if (ChatFeed.KIND_CLIENT.equals(entry.kind())
                    && ChatFeed.CLIENT_NAME.equals(entry.fromName())) {
                found = entryToMap(entry);
            }
        }
        return found;
    }

    // ── POST /api/email ─────────────────────────────────────────────────

    private void handleEmail(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, error("method not allowed"));
            return;
        }
        if (view == null) {
            sendJson(ex, 500, error("company view unavailable"));
            return;
        }
        String bodyText = readBody(ex);
        if (bodyText == null) {
            sendJson(ex, 413, error("request body too large"));
            return;
        }
        String roleId;
        String subject;
        String content;
        try {
            Map<String, Object> body = JSON.readMap(bodyText);
            roleId = field(body, "roleId");
            subject = field(body, "subject");
            content = field(body, "content");
        } catch (RuntimeException e) {
            sendJson(ex, 400, error("invalid json body"));
            return;
        }
        if (roleId.isEmpty() || subject.isEmpty() || content.isEmpty()) {
            sendJson(ex, 400, error("role, subject, and content are required"));
            return;
        }
        RoleSpec recipient = view.roster().stream()
                .filter(spec -> spec != null && spec.id() != null && roleId.equals(spec.id().value()))
                .findFirst().orElse(null);
        if (recipient == null) {
            sendJson(ex, 404, error("target role not found"));
            return;
        }
        view.sendEmail(new RoleId(roleId), subject, content);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("roleId", roleId);
        resp.put("roleName", Text.orEmpty(recipient.name()));
        sendJson(ex, 200, resp);
    }

    private static String field(Map<String, Object> body, String name) {
        Object value = body.get(name);
        return value == null ? "" : String.valueOf(value).strip();
    }

    // ── POST /api/pause | /api/resume ───────────────────────────

    private void handlePause(HttpExchange ex, boolean pause) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, error("method not allowed"));
            return;
        }
        if (view == null) {
            sendJson(ex, 500, error("company view unavailable"));
            return;
        }
        String reason = "";
        String bodyText = readBody(ex);
        if (bodyText == null) {
            sendJson(ex, 413, error("request body too large"));
            return;
        }
        if (!bodyText.isBlank()) {
            try {
                Map<String, Object> body = JSON.readMap(bodyText);
                Object value = body.get("reason");
                reason = value == null ? "" : String.valueOf(value).strip();
            } catch (RuntimeException e) {
                sendJson(ex, 400, error("invalid json body"));
                return;
            }
        }
        if (pause) {
            view.pause(reason.isEmpty() ? "paused via the Web UI" : reason);
        } else {
            view.resume();
        }
        CompanyStatus status = view.status();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("paused", status != null && status.paused());
        resp.put("pauseReason", status == null ? "" : Text.orEmpty(status.pauseReason()));
        sendJson(ex, 200, resp);
    }

    // ── 静态资源 ────────────────────────────────────────────────

    private void handleStatic(HttpExchange ex, String path) throws IOException {
        String name = "/".equals(path) ? "index.html" : path.substring(1);
        if (name.contains("..") || name.contains("\\")) {
            sendJson(ex, 404, error("not found"));
            return;
        }
        byte[] content = readResource("web/" + name);
        if (content == null) {
            sendJson(ex, 404, error("not found: " + name));
            return;
        }
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "html";
        ex.getResponseHeaders().set("Content-Type", MIME.getOrDefault(ext, "application/octet-stream"));
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

    // ── 工具方法 ────────────────────────────────────────────────

    /** 刷新浏览器心跳（仅 Web 通道需要）。 */
    private void touchAttach() {
        if (feed instanceof ChatFeed chatFeed) {
            chatFeed.touchAttach();
        }
    }

    /** 读请求体；超过上限返回 null（调用方回 413）。 */
    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(MAX_BODY_BYTES + 1);
            if (data.length > MAX_BODY_BYTES) {
                return null;
            }
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> error(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("reason", reason);
        return out;
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 500 等错误响应的发送失败只能记日志，不能再抛。 */
    private static void trySend(HttpExchange ex, int status, Object body) {
        try {
            sendJson(ex, status, body);
        } catch (IOException e) {
            logger.warn("ChatWebServer: 发送 {} 响应失败", status, e);
        }
    }
}
