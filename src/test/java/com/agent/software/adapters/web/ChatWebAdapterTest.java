package com.agent.software.adapters.web;

import com.agent.software.web.ChatStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWebAdapterTest {

    private ChatWebAdapter adapter;
    private final AtomicReference<String> paused = new AtomicReference<>();
    private final AtomicReference<Boolean> resumed = new AtomicReference<>(false);
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        ChatStore store = new ChatStore();
        adapter = new ChatWebAdapter(store,
                () -> Map.of("ok", true, "day", 1, "groups", List.of(), "client_talk", Map.of("active", false)),
                paused::set,
                () -> resumed.set(true),
                "127.0.0.1", 0);
        adapter.start();
    }

    @AfterEach
    void stop() {
        if (adapter != null) {
            adapter.stop();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + adapter.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + adapter.port() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void stateAndMessagesAreServed() throws Exception {
        HttpResponse<String> state = get("/api/v1/state");
        assertEquals(200, state.statusCode());
        assertTrue(state.body().contains("\"day\":1"));
        assertTrue(state.body().contains("client_talk"));

        HttpResponse<String> messages = get("/api/v1/messages?since=0");
        assertEquals(200, messages.statusCode());
        assertTrue(messages.body().contains("last_seq"));
    }

    @Test
    void pauseResumeAndAttachWork() throws Exception {
        HttpResponse<String> pause = post("/api/v1/pause", "{\"reason\":\"quota\"}");
        assertEquals(200, pause.statusCode());
        assertEquals("quota", paused.get());

        assertEquals(200, post("/api/v1/resume", "").statusCode());
        assertTrue(resumed.get());

        assertEquals(200, post("/api/v1/attach", "").statusCode());
    }

    @Test
    void replyRequiresPostAndPendingConversation() throws Exception {
        assertEquals(405, get("/api/v1/reply").statusCode());
        assertEquals(409, post("/api/v1/reply", "{\"text\":\"hi\"}").statusCode());
        assertEquals(400, post("/api/v1/reply", "{\"text\":\"   \"}").statusCode());
    }

    @Test
    void unknownApiAndTraversalAreRejected() throws Exception {
        assertEquals(404, get("/api/v1/nope").statusCode());
        assertFalse(get("/api/v1/state").body().isEmpty());
    }
}
