package com.agent.software.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingClientTest {
    private HttpServer server;
    private EmbeddingClient client;
    private volatile String responseBody;
    private volatile int status = 200;
    private volatile JsonNode requestBody;
    private volatile String authorization;
    private volatile String method;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            method = exchange.getRequestMethod();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            requestBody = new ObjectMapper().readTree(exchange.getRequestBody());
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        client = new EmbeddingClient(endpoint("test-key"), Duration.ofSeconds(5));
    }

    private ProviderResolver.Endpoint endpoint(String key) {
        return new ProviderResolver.Endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                key, "embedding-model");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsBatchAndRestoresInputOrder() throws Exception {
        responseBody = """
                {"data":[{"index":1,"embedding":[0.5,-0.25]},
                         {"index":0,"embedding":[1,0]}]}
                """;
        var vectors = client.embed(List.of("hello", "你好"));
        assertEquals(List.of(List.of(1.0, 0.0), List.of(0.5, -0.25)), vectors);
        assertEquals("POST", method);
        assertEquals("Bearer test-key", authorization);
        assertEquals("embedding-model", requestBody.path("model").asText());
        assertEquals("float", requestBody.path("encoding_format").asText());
        assertEquals(new ObjectMapper().valueToTree(List.of("hello", "你好")), requestBody.path("input"));
        assertThrows(UnsupportedOperationException.class, () -> vectors.getFirst().add(2.0));
    }

    @Test
    void embedsSingleTextWithoutAuthentication() throws Exception {
        responseBody = "{\"data\":[{\"index\":0,\"embedding\":[0.2,0.3]}]}";
        assertEquals(List.of(0.2, 0.3), new EmbeddingClient(endpoint("")).embed("hello"));
        assertNull(authorization);
    }

    @Test
    void reportsHttpErrors() {
        status = 401;
        responseBody = "{\"error\":{\"message\":\"unauthorized\"}}";
        IOException error = assertThrows(IOException.class, () -> client.embed("hello"));
        assertTrue(error.getMessage().contains("401"));
    }

    @Test
    void rejectsMalformedResponses() {
        for (String body : List.of("not json", "null", "{}", "{\"data\":[]}",
                "{\"data\":[{\"index\":1,\"embedding\":[1]}]}",
                "{\"data\":[{\"embedding\":[1]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[\"bad\"]}]}")) {
            responseBody = body;
            assertThrows(IOException.class, () -> client.embed("hello"), body);
        }
    }

    @Test
    void rejectsDuplicateIndicesAndInconsistentDimensions() {
        for (String body : List.of(
                "{\"data\":[{\"index\":0,\"embedding\":[1]},{\"index\":0,\"embedding\":[2]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[1]},{\"index\":1,\"embedding\":[2,3]}]}")) {
            responseBody = body;
            assertThrows(IOException.class, () -> client.embed(List.of("a", "b")));
        }
    }

    @Test
    void validatesInputsBeforeSending() {
        assertThrows(IllegalArgumentException.class, () -> client.embed(List.of()));
        assertThrows(IllegalArgumentException.class, () -> client.embed((String) null));
        assertThrows(IllegalArgumentException.class, () -> client.embed(Arrays.asList("hello", null)));
        assertThrows(IllegalArgumentException.class, () -> client.embed("  "));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingClient(endpoint(""), Duration.ZERO));
        assertNull(requestBody);
    }
}
