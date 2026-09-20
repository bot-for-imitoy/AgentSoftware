package com.agent.software.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Calls OpenAI-compatible {@code /embeddings} endpoints.
 * Supply an embedding model explicitly in {@link ProviderResolver.Endpoint};
 * the provider catalog's default chat model may not support embeddings.
 * Each call makes one request, with no automatic retries.
 */
public final class EmbeddingClient implements EmbeddingModel {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final URI url;
    private final String model;
    private final String apiKey;
    private final Duration timeout;

    public EmbeddingClient(ProviderResolver.Endpoint endpoint) {
        this(endpoint, Duration.ofSeconds(60));
    }

    /** Base URL includes the API prefix (for example, {@code https://host/v1}). */
    public EmbeddingClient(ProviderResolver.Endpoint endpoint, Duration timeout) {
        Objects.requireNonNull(endpoint, "endpoint");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (endpoint.baseUrl() == null || endpoint.baseUrl().isBlank()
                || endpoint.model() == null || endpoint.model().isBlank()) {
            throw new IllegalArgumentException("baseUrl and embedding model are required");
        }
        String base = endpoint.baseUrl().trim().replaceAll("/+$", "");
        this.url = URI.create(base + "/embeddings");
        if (!("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                || url.getHost() == null || url.getRawQuery() != null || url.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl must be an HTTP(S) URL without query or fragment");
        }
        this.model = endpoint.model().trim();
        this.apiKey = endpoint.apiKey() == null ? "" : endpoint.apiKey().trim();
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** Returns the vector for one nonblank text. */
    @Override
    public List<Double> embed(String input) throws IOException, InterruptedException {
        return embed(Collections.singletonList(input)).getFirst();
    }

    /**
     * Returns immutable vectors in input order, using response indices to reorder results.
     * @throws IllegalArgumentException if inputs are empty or contain null/blank text
     * @throws IOException on transport errors, non-2xx status, or malformed responses
     * @throws InterruptedException if the caller interrupts the HTTP request
     */
    public List<List<Double>> embed(List<String> inputs) throws IOException, InterruptedException {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("at least one input is required");
        }
        List<String> texts = new ArrayList<>(inputs);
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("inputs must contain only nonblank text");
            }
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", model);
        payload.set("input", mapper.valueToTree(texts));
        payload.put("encoding_format", "float");
        HttpRequest.Builder request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = http.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Embedding request failed: HTTP " + response.statusCode());
        }
        return parseVectors(response.body(), texts.size());
    }

    private List<List<Double>> parseVectors(String body, int count) throws IOException {
        JsonNode root = mapper.readTree(body);
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray() || data.size() != count) {
            throw new IOException("Embedding response must contain one vector per input");
        }
        List<List<Double>> vectors = new ArrayList<>(Collections.nCopies(count, null));
        int dimensions = -1;
        for (JsonNode item : data) {
            JsonNode indexNode = item.path("index");
            if (!indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
                throw new IOException("Embedding response contains an invalid index");
            }
            int index = indexNode.intValue();
            if (index < 0 || index >= count || vectors.get(index) != null) {
                throw new IOException("Embedding response contains an out-of-range or duplicate index");
            }
            JsonNode values = item.path("embedding");
            if (!values.isArray() || values.isEmpty()
                    || (dimensions != -1 && values.size() != dimensions)) {
                throw new IOException("Embedding response contains empty or inconsistent vectors");
            }
            dimensions = values.size();
            List<Double> vector = new ArrayList<>(dimensions);
            for (JsonNode value : values) {
                if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                    throw new IOException("Embedding vector contains a non-finite or nonnumeric value");
                }
                vector.add(value.doubleValue());
            }
            vectors.set(index, List.copyOf(vector));
        }
        return List.copyOf(vectors);
    }
}
