package com.agent.software.web;

import com.agent.software.AgentSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatWebServer tests: HTTP endpoint smoke tests (JDK HttpServer, random port).
 */
class ChatWebServerTest {

    private AgentSystem system;
    private ChatWebServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        system = new AgentSystem();   // empty role pool (group roster comes from templates)
        server = new ChatWebServer(system, "127.0.0.1", 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(HttpResponse<String> r) {
        try {
            return com.agent.software.utils.Json.parseObject(r.body());
        } catch (Exception e) {
            throw new AssertionError("response is not valid JSON: " + r.body(), e);
        }
    }

    // ── Static pages ─────────────────────────────────────────

    @Test
    void testIndexServed() throws Exception {
        HttpResponse<String> r = get("/");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("AgentCompany"));
        assertTrue(r.headers().firstValue("Content-Type").orElse("").contains("text/html"));

        HttpResponse<String> js = get("/app.js");
        assertEquals(200, js.statusCode());
        assertTrue(js.body().contains("pollState"));
    }

    // ── /api/state ────────────────────────────────────────

    @Test
    void testStateListsGroupsLeadershipFirst() throws Exception {
        HttpResponse<String> r = get("/api/state");
        assertEquals(200, r.statusCode());
        Map<String, Object> body = json(r);
        assertTrue((Boolean) body.get("ok"));
        assertTrue((Integer) body.get("day") >= 1);

        List<Map<String, Object>> groups = (List<Map<String, Object>>) body.get("groups");
        assertNotNull(groups);
        assertFalse(groups.isEmpty());
        assertEquals("Leadership Group", groups.get(0).get("key"));
        assertEquals("Leadership Group", groups.get(0).get("label"));
        // the Leadership Group contains CEO Lin Zong
        List<Map<String, Object>> members = (List<Map<String, Object>>) groups.get(0).get("members");
        assertTrue(members.stream().anyMatch(m -> "CEO".equals(m.get("roleId")) && "Lin Zong".equals(m.get("name"))));

        Map<String, Object> ct = (Map<String, Object>) body.get("clientTalk");
        assertFalse((Boolean) ct.get("active"));
    }

    // ── /api/messages and /api/reply ────────────────────────

    @Test
    void testMessagesEmptyInitially() throws Exception {
        HttpResponse<String> r = get("/api/messages?since=0");
        assertEquals(200, r.statusCode());
        Map<String, Object> body = json(r);
        assertEquals(0L, ((Number) body.get("lastSeq")).longValue());
        assertTrue(((List<?>) body.get("messages")).isEmpty());
    }

    @Test
    void testReplyWithoutPendingConversationRejected() throws Exception {
        HttpResponse<String> r = post("/api/reply", "{\"text\":\"Hello\"}");
        assertEquals(409, r.statusCode());
        assertFalse((Boolean) json(r).get("ok"));
    }

    @Test
    void testReplyEmptyRejected() throws Exception {
        HttpResponse<String> r = post("/api/reply", "{\"text\":\"   \"}");
        assertEquals(400, r.statusCode());
        HttpResponse<String> bad = post("/api/reply", "not-json");
        assertEquals(400, bad.statusCode());
    }

    // ── Full round trip: leader waits → page reply ────────────

    @Test
    void testFullClientReplyFlow() throws Exception {
        ChatStore store = system.chatStore;
        assertNotNull(store);
        store.markAttached();

        store.beginClientWait("CEO", "Lin Zong", "Leadership Group");

        // the client replies on the page
        HttpResponse<String> r = post("/api/reply", "{\"text\":\"Please develop a payment system for me\"}");
        assertEquals(200, r.statusCode());
        Map<String, Object> body = json(r);
        assertTrue((Boolean) body.get("ok"));
        Map<String, Object> msg = (Map<String, Object>) body.get("message");
        assertEquals("Client A", msg.get("fromName"));
        assertEquals("CEO", msg.get("toRoleId"));

        // the waiting member receives the reply
        String reply = store.awaitClientReply(5000);
        assertEquals("Please develop a payment system for me", reply);

        // clientTalk.active in the state is true while waiting
        HttpResponse<String> s = get("/api/state");
        Map<String, Object> ct = (Map<String, Object>) json(s).get("clientTalk");
        assertTrue((Boolean) ct.get("active"));

        store.endClientWait();
        s = get("/api/state");
        ct = (Map<String, Object>) json(s).get("clientTalk");
        assertFalse((Boolean) ct.get("active"));
    }
}
