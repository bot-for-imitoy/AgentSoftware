package com.agent.software.adapters.web;

import com.agent.software.utils.Json;
import com.agent.software.web.ChatStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Web UI / HTTP API adapter on the JDK http server.
 *
 * <p>Same job as the legacy {@code ChatWebServer} but with a versioned, stable
 * surface: all routes live under {@code /api/v1/}, payload keys are
 * {@code snake_case}, and non-GET/POST methods are rejected explicitly.
 */
public final class ChatWebAdapter {

    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_PORT = 8787;

    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "json", "application/json; charset=utf-8",
            "txt", "text/plain; charset=utf-8");

    private final ChatStore store;
    private final Supplier<Map<String, Object>> stateSupplier;
    private final Consumer<String> onPause;
    private final Runnable onResume;

    private final HttpServer server;
    private final String host;
    private final int port;

    public ChatWebAdapter(ChatStore store, Supplier<Map<String, Object>> stateSupplier,
                          Consumer<String> onPause, Runnable onResume) throws IOException {
        this(store, stateSupplier, onPause, onResume, DEFAULT_HOST, DEFAULT_PORT);
    }

    public ChatWebAdapter(ChatStore store, Supplier<Map<String, Object>> stateSupplier,
                          Consumer<String> onPause, Runnable onResume,
                          String host, int port) throws IOException {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
        this.stateSupplier = stateSupplier == null ? Map::of : stateSupplier;
        this.onPause = onPause == null ? reason -> {
        } : onPause;
        this.onResume = onResume == null ? () -> {
        } : onResume;
        this.host = host == null || host.isBlank() ? DEFAULT_HOST : host;
        this.server = HttpServer.create(new InetSocketAddress(this.host, Math.max(0, port)), 0);
        this.port = server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return port;
    }

    public String host() {
        return host;
    }

    public String url() {
        return "http://127.0.0.1:" + port + "/";
    }

    // ── routing ────────────────────────────────────────────────────────

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                store.markAttached();
                handleApi(exchange, path);
                return;
            }
            handleStatic(exchange, path);
        } catch (RuntimeException e) {
            sendJson(exchange, 500, Map.of("ok", false, "reason", "internal error: " + e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange, String path) throws IOException {
        switch (path) {
            case "/api/v1/state" -> sendJson(exchange, 200, stateSupplier.get());
            case "/api/v1/messages" -> sendJson(exchange, 200, messages(exchange));
            case "/api/v1/reply" -> reply(exchange);
            case "/api/v1/pause" -> pause(exchange);
            case "/api/v1/resume" -> resume(exchange);
            case "/api/v1/attach" -> sendJson(exchange, 200, Map.of("ok", true, "attached", true));
            default -> sendJson(exchange, 404, Map.of("ok", false, "reason", "unknown api: " + path));
        }
    }

    private Map<String, Object> messages(HttpExchange exchange) {
        long since = 0;
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && parts[0].equals("since")) {
                    try {
                        since = Long.parseLong(parts[1]);
                    } catch (NumberFormatException ignored) {
                        since = 0;
                    }
                }
            }
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Map<String, Object> message : store.messagesSince(since)) {
            messages.add(snakeCase(message));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("last_seq", store.lastSeq());
        response.put("messages", messages);
        return response;
    }

    private void reply(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        if (body == null) {
            return;
        }
        String text = Json.str(body, "text", "").strip();
        if (text.isEmpty()) {
            sendJson(exchange, 400, Map.of("ok", false, "reason", "reply text must not be empty"));
            return;
        }
        ChatStore.ChatMessage recorded = store.postClientReply(text);
        if (recorded == null) {
            sendJson(exchange, 409, Map.of("ok", false, "reason", "no client dialogue is currently pending"));
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("delivered", true);
        response.put("message", snakeCase(ChatStore.toMap(recorded)));
        sendJson(exchange, 200, response);
    }

    private void pause(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        if (body == null) {
            return;
        }
        String reason = Json.str(body, "reason", "").strip();
        onPause.accept(reason.isEmpty() ? "paused via the Web UI" : reason);
        sendJson(exchange, 200, Map.of("ok", true, "paused", true,
                "pause_reason", reason.isEmpty() ? "paused via the Web UI" : reason));
    }

    private void resume(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }
        onResume.run();
        sendJson(exchange, 200, Map.of("ok", true, "paused", false, "pause_reason", ""));
    }

    private boolean requirePost(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("ok", false, "reason", "method not allowed"));
            return false;
        }
        return true;
    }

    private Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return Json.parseObject(text);
        } catch (IOException e) {
            sendJson(exchange, 400, Map.of("ok", false, "reason", "invalid json body"));
            return null;
        }
    }

    private void handleStatic(HttpExchange exchange, String path) throws IOException {
        String name = path.equals("/") ? "index.html" : path.substring(1);
        if (name.contains("..") || name.contains("\\")) {
            sendJson(exchange, 404, Map.of("ok", false, "reason", "not found"));
            return;
        }
        byte[] content = readResource("web/" + name);
        if (content == null) {
            sendJson(exchange, 404, Map.of("ok", false, "reason", "not found: " + name));
            return;
        }
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "html";
        exchange.getResponseHeaders().set("Content-Type", MIME.getOrDefault(ext, "application/octet-stream"));
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(content);
        }
    }

    private static byte[] readResource(String path) {
        try (InputStream in = ChatWebAdapter.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, Object> snakeCase(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            out.put(snake(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static String snake(String key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Sort helper kept for callers that build group lists. */
    static Comparator<Map<String, Object>> byKey() {
        return Comparator.comparing(m -> String.valueOf(m.get("key")));
    }
}
