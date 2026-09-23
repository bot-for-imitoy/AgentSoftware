package com.agent.software.llm;

import com.agent.software.store.ConfigStore;
import com.agent.software.utils.Json;
import com.agent.software.utils.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 Embedding 客户端：{@code POST {base_url}/embeddings}，body
 * {@code {"model": ..., "input": "..."}}，取 {@code data[0].embedding}。
 *
 * <p>配置解析顺序与 {@link OpenAICompatLLM} 一致（环境变量 &gt; 配置文件 &gt; 默认值），
 * 但键名是 {@code embedding.*}，并且 {@code api_key}/{@code base_url} 会**回落到 {@code llm.*}**
 * ——同一个网关既跑对话又跑 embedding 时不用重复填。模型名不回落到对话模型（那一定是错的）。
 *
 * <p>熔断：连续 {@value #MAX_CONSECUTIVE_ERRORS} 次失败后彻底停用（只 warn 一次）。
 * 没有这条的话，模型名写错会让**每一条消息**都去重试 3 次，把任务循环拖死。
 */
public class OpenAICompatEmbedding extends Embedding {

    private static final Logger logger = LoggerFactory.getLogger(OpenAICompatEmbedding.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_MILLIS = 1000L;
    /** 连续失败多少次就放弃（进程内不再尝试）。 */
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private volatile int consecutiveErrors = 0;
    private volatile boolean disabled = false;
    private volatile int dimension = 0;

    public OpenAICompatEmbedding(String apiKey, String model, ConfigStore config) {
        this.apiKey = firstNonBlank(apiKey,
                env("OPENAI_EMBEDDING_API_KEY"), configString(config, "embedding.api_key"),
                env("OPENAI_API_KEY"), configString(config, "llm.api_key"));
        this.model = firstNonBlank(model,
                env("OPENAI_EMBEDDING_MODEL"), configString(config, "embedding.model"));
        this.baseUrl = normalizeBaseUrl(stripTrailingSlash(firstNonBlank(
                env("OPENAI_EMBEDDING_BASE_URL"), configString(config, "embedding.base_url"),
                env("OPENAI_BASE_URL"), configString(config, "llm.base_url"),
                "https://api.openai.com/v1")));
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public String getEndpoint() {
        return baseUrl;
    }

    @Override
    public boolean isConfigured() {
        return model != null && !model.isBlank();
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public double[] embed(String text) {
        if (!isConfigured() || disabled || text == null || text.isBlank()) {
            return EMPTY;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", text);
        String payload = Json.stringify(body);

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/embeddings"))
                        .timeout(Duration.ofMinutes(2))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload));
                if (apiKey != null && !apiKey.isBlank()) {
                    rb.header("Authorization", "Bearer " + apiKey);
                }
                HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    double[] v = parseEmbedding(resp.body());
                    if (v.length == 0) {
                        recordFailure("malformed embedding response: "
                                + Text.truncate(resp.body(), 200));
                        return EMPTY;
                    }
                    consecutiveErrors = 0;
                    dimension = v.length;
                    logger.info("Embedding ok: model={}, dim={}", model, v.length);
                    return v;
                }
                if (isRetryable(status) && attempt < MAX_ATTEMPTS) {
                    logger.warn("Embedding HTTP {} (attempt {}/{}), retrying", status, attempt, MAX_ATTEMPTS);
                    sleep(RETRY_BASE_MILLIS * attempt);
                    continue;
                }
                // 4xx（模型不存在 / 未授权）不是暂时性问题，直接熔断，不重试
                recordFailure("HTTP " + status + " " + Text.truncate(resp.body(), 200));
                if (status >= 400 && status < 500) {
                    disabled = true;
                    logger.warn("Embedding disabled for this session (HTTP {} from {}): semantic memory "
                            + "will stay off until the config is fixed", status, baseUrl);
                }
                return EMPTY;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return EMPTY;
            } catch (Exception e) {
                lastError = e;
                if (attempt < MAX_ATTEMPTS) {
                    logger.warn("Embedding request failed (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, e.toString());
                    sleep(RETRY_BASE_MILLIS * attempt);
                    continue;
                }
            }
        }
        recordFailure(lastError == null ? "unknown" : lastError.toString());
        return EMPTY;
    }

    private void recordFailure(String reason) {
        int errors = ++consecutiveErrors;
        if (errors >= MAX_CONSECUTIVE_ERRORS && !disabled) {
            disabled = true;
            logger.warn("Embedding disabled after {} consecutive failures ({}); semantic memory off for "
                    + "this session", errors, reason);
        } else {
            logger.warn("Embedding failed ({}): {}", errors, reason);
        }
    }

    /**
     * 解析 {@code data[0].embedding}。
     *
     * <p>允许网关把数字给成 int/double/字符串，非数字项按 0 处理；结构不对返回空数组。
     */
    static double[] parseEmbedding(String body) {
        if (body == null || body.isBlank()) {
            return EMPTY;
        }
        Map<String, Object> root;
        try {
            root = Json.parseObject(body);
        } catch (Exception e) {
            return EMPTY;
        }
        Object dataObj = root.get("data");
        if (!(dataObj instanceof List<?> data) || data.isEmpty()) {
            return EMPTY;
        }
        if (!(data.get(0) instanceof Map<?, ?> first)) {
            return EMPTY;
        }
        Object embObj = first.get("embedding");
        if (!(embObj instanceof List<?> nums) || nums.isEmpty()) {
            return EMPTY;
        }
        double[] out = new double[nums.size()];
        for (int i = 0; i < nums.size(); i++) {
            out[i] = toDouble(nums.get(i));
        }
        return out;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    /** 只给域名（路径为空或 "/"）时自动补 "/v1"，与 {@link OpenAICompatLLM} 同一口径。 */
    static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        url = stripTrailingSlash(url);
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return url + "/v1";
            }
        } catch (IllegalArgumentException ignored) {
        }
        return url;
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status == 408 || status >= 500;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    private static String configString(ConfigStore config, String key) {
        if (config == null) {
            return null;
        }
        Object v = config.get(key, null);
        return v == null ? null : String.valueOf(v);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) {
            return "";
        }
        String out = s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
