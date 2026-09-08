package com.agent.software.llm;

import com.agent.software.store.ConfigStore;
import com.agent.software.utils.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Unified OpenAI-compatible chat/completions client (the OpenAICompatLLM of the Python llm.py).
 *
 * <p>All backends (OpenAI official / DeepSeek / local vLLM, LM Studio, etc.) use exactly the same
 * request protocol (OpenAI format), so providers are no longer distinguished: the client layer only
 * has the {@link LLM} interface plus this implementation, and reads only OpenAI-format environment variables.
 *
 * <p><b>Unified config resolution priority</b> (high → low, consistent with {@link LayeredConfig}):
 * <ol>
 *   <li>Constructor explicit parameters (apiKey / baseUrl / model);</li>
 *   <li>Java arguments: system properties {@code -DOPENAI_API_KEY=...} etc. (key names match environment variables);</li>
 *   <li>Environment variables: {@code OPENAI_API_KEY} / {@code OPENAI_BASE_URL} / {@code OPENAI_MODEL};</li>
 *   <li>Config files: {@link ConfigStore} dotted paths {@code llm.api_key} / {@code llm.base_url} /
 *       {@code llm.model};</li>
 *   <li>Default values.</li>
 * </ol>
 */
public class OpenAICompatLLM implements LLM {

    private static final Logger logger = LoggerFactory.getLogger(OpenAICompatLLM.class);

    /** OpenAI-format environment variable names (system properties use the same key names). */
    public static final String API_KEY_ENV = "OPENAI_API_KEY";
    public static final String BASE_URL_ENV = "OPENAI_BASE_URL";
    public static final String MODEL_ENV = "OPENAI_MODEL";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    // ── Request failure retry (user-specified) ─────────────
    // Recoverable errors retry after retryDelay seconds (10s), up to retryMax times (200);
    // unrecoverable client errors (400/401/403/404 etc.) are not retried.
    public double retryDelay = 10.0;
    public int retryMax = 200;
    public int apiTimeoutSeconds = 120;

    /** Log prefix (e.g. "OpenAI"). */
    public String apiName = "OpenAI";

    protected String apiKey;
    protected String baseUrl;
    protected String model;
    protected String label = "";                 // role label (DEBUG log prefix)
    protected String retryError = "";            // reason of the most recent request failure
    protected final ConfigStore configStore;

    // ── System pause / auto-pause integration ──────────────────
    /** Invoked when the API reports an exhausted account balance/quota (auto-pause hook). */
    protected Consumer<String> onInsufficientBalance = null;
    /** While this supplier returns true (the system is paused) no request is sent/retried. */
    protected Supplier<Boolean> pauseGate = null;

    /** Lowercased error-body markers that mean the account balance/quota ran out. */
    private static final String[] INSUFFICIENT_BALANCE_MARKERS = {
            "insufficient_quota", "insufficient quota", "insufficient balance",
            "exceeded your current quota", "out of credits", "not enough balance",
            "no enough balance", "payment required", "quota exceeded",
            "余额不足", "账户余额", "余额已用完", "欠费", "额度不足",
    };

    /** Convenience constructor: everything goes through layered config (system property &gt; environment variable &gt; ConfigStore &gt; default value). */
    public OpenAICompatLLM() {
        this(null, null, null, null, null);
    }

    /**
     * Full constructor.
     *
     * @param apiKey      explicit API key (null = resolve via layered config {@code OPENAI_API_KEY})
     * @param baseUrl     explicit Base URL (null = resolve via layered config {@code OPENAI_BASE_URL})
     * @param model       explicit model name (null = resolve via layered config {@code OPENAI_MODEL})
     * @param label       role label (DEBUG log prefix)
     * @param configStore config store (null = default path)
     */
    public OpenAICompatLLM(String apiKey, String baseUrl, String model,
                           String label, ConfigStore configStore) {
        this(apiKey, baseUrl, model, label, configStore, null, null);
    }

    /**
     * Test injection variant: env / props are config snapshots (null = read real system properties /
     * environment variables), consistent with PathManager's injection approach.
     */
    OpenAICompatLLM(String apiKey, String baseUrl, String model,
                    String label, ConfigStore configStore,
                    Map<String, String> env, Map<String, String> props) {
        this.configStore = configStore != null ? configStore : new ConfigStore();
        this.label = label != null ? label : "";
        this.apiKey = resolve(apiKey, API_KEY_ENV, "llm.api_key", null, env, props);
        this.baseUrl = stripSlash(resolve(baseUrl, BASE_URL_ENV, "llm.base_url", DEFAULT_BASE_URL, env, props));
        this.model = resolve(model, MODEL_ENV, "llm.model", DEFAULT_MODEL, env, props);
    }

    /**
     * Layered config resolution for a single setting, high → low priority (see the class javadoc):
     * explicit constructor argument &gt; system property {@code -D<envName>=...} &gt; environment
     * variable {@code <envName>} &gt; ConfigStore dotted path {@code storeKey} &gt; default value.
     *
     * <p>When {@code props} / {@code env} are null the real system properties / environment variables
     * are read; non-null maps (test snapshots) replace them entirely, keeping tests deterministic.
     */
    private String resolve(String explicit, String envName, String storeKey, String def,
                           Map<String, String> env, Map<String, String> props) {
        if (explicit != null) {
            return explicit;
        }
        String fromProps = props != null ? props.get(envName) : System.getProperty(envName);
        if (fromProps != null) {
            return fromProps;
        }
        String fromEnv = env != null ? env.get(envName) : System.getenv(envName);
        if (fromEnv != null) {
            return fromEnv;
        }
        Object fromStore = configStore.get(storeKey, null);
        return fromStore != null ? String.valueOf(fromStore) : def;
    }

    private static String stripSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // ── Pause / auto-pause wiring (set by the owning system) ──

    /**
     * Install the auto-pause hook: called once per exhausted-account error with a short reason
     * (e.g. "API reported insufficient balance/quota (HTTP 402)") so the owning AgentSystem can
     * pause the whole simulation.
     */
    public void setOnInsufficientBalance(Consumer<String> listener) {
        this.onInsufficientBalance = listener;
    }

    /**
     * Install the pause gate: while it returns true (the owning AgentSystem is paused) this client
     * sends no new request and aborts retries — the caller receives "[API error: aborted: system
     * paused]" so in-flight tasks stop cleanly and held tasks resume later.
     */
    public void setPauseGate(Supplier<Boolean> gate) {
        this.pauseGate = gate;
    }

    /** Whether the owning system is paused right now (false when no pause gate is installed). */
    protected boolean isSystemPaused() {
        if (pauseGate == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(pauseGate.get());
        } catch (Exception e) {
            logger.debug("{} pause-gate check failed", apiName, e);
            return false;
        }
    }

    /** Whether an API error response means the account balance/quota ran out (auto-pause trigger). */
    static boolean isInsufficientBalance(int status, String body) {
        if (status == 402) {
            return true;  // 402 Payment Required — the canonical exhausted-account status
        }
        if (body == null || body.isEmpty()) {
            return false;
        }
        String lower = body.toLowerCase();
        for (String marker : INSUFFICIENT_BALANCE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Log the exhausted-account failure and invoke the auto-pause hook (if installed). */
    private void notifyInsufficientBalance(int status) {
        String reason = "API reported insufficient balance/quota (HTTP " + status + ")";
        if (onInsufficientBalance != null) {
            try {
                onInsufficientBalance.accept(reason);
            } catch (Exception e) {
                logger.warn("{} insufficient-balance auto-pause hook failed", apiName, e);
            }
        }
    }

    // ── Debug logging (with role prefix) ──────────────────

    protected void debug(String msg, Object... args) {
        if (label != null && !label.isEmpty()) {
            logger.debug("[" + label + "] " + msg, args);
        } else {
            logger.debug(msg, args);
        }
    }

    // ── Public API (same interface as MockLLM) ─────────────

    @Override
    public LLM.ChatResponse chat(String system, String user, double temperature, Integer maxTokens) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (system != null && !system.isEmpty()) {
            messages.add(msg("system", system));
            debug("chat: appended system message ({} chars)", system.length());
        }
        messages.add(msg("user", user));
        debug("chat: appended user message ({} chars)", user.length());

        int mt = maxTokens != null ? maxTokens : 512;
        Object[] result = callApi(messages, temperature, mt);
        String text = (String) result[0];
        String reasoning = (String) result[2];
        Map<String, Object> usage = (Map<String, Object>) result[1];
        int tokens = usage != null ? intOf(usage.get("total_tokens")) : 0;
        return new LLM.ChatResponse(text, reasoning, tokens);
    }

    @Override
    public LLM.ChatResponse summarize(String logText, double temperature, Integer maxTokens) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", "You are a professional assistant in charge of writing work summaries. "
                + "Summarize the following content in concise English, "
                + "extracting key decisions, action items, and noteworthy low-priority events. "
                + "Output format: first a summary paragraph, then a list of key decisions and action items.");
        messages.add(system);
        messages.add(msg("user", "Please summarize today's work log:\n" + logText));
        debug("summarize: appended user message ({} chars)", logText.length());

        int mt = maxTokens != null ? maxTokens : 256;
        Object[] result = callApi(messages, temperature, mt);
        String text = (String) result[0];
        String reasoning = (String) result[2];
        Map<String, Object> usage = (Map<String, Object>) result[1];
        int tokens = usage != null ? intOf(usage.get("total_tokens")) : 0;
        return new LLM.ChatResponse(text, reasoning, tokens);
    }

    @Override
    public LLM.ToolsResponse chatWithTools(List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools,
                                           double temperature, Integer maxTokens) {
        URI url = URI.create(baseUrl + "/v1/chat/completions");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", temperature);
        payload.put("tools", tools);
        payload.put("tool_choice", "auto");
        if (maxTokens != null) {
            payload.put("max_tokens", maxTokens);
        }
        debug("{} API call (tools): model={} messages={} tools={}",
                apiName, model, messages.size(), tools.size());

        Map<String, Object> data = postWithRetry(url, payload);
        if (data == null) {
            return new LLM.ToolsResponse("[API error: " + retryError + "]", List.of(), null);
        }
        Map<String, Object> message = choiceMessage(data);
        String content = strOf(message.get("content"));
        Object reasoningObj = message.get("reasoning_content");
        if (reasoningObj == null) {
            reasoningObj = message.get("reasoning");
        }
        String reasoning = strOf(reasoningObj);
        List<Map<String, Object>> rawCalls = listOfMaps(message.get("tool_calls"));
        if (!reasoning.isEmpty()) {
            debug("{} reasoning ({} chars)", apiName, reasoning.length());
        }
        if (content.isEmpty() && !reasoning.isEmpty() && rawCalls.isEmpty()) {
            logger.warn("{}: empty content, falling back to reasoning_content", apiName);
            content = reasoning;
        }
        Map<String, Object> usage = mapOf(data.get("usage"));
        if (!usage.isEmpty()) {
            debug("{} tokens: prompt={} completion={} total={}", apiName,
                    usage.getOrDefault("prompt_tokens", "?"),
                    usage.getOrDefault("completion_tokens", "?"),
                    usage.getOrDefault("total_tokens", "?"));
        }
        return new LLM.ToolsResponse(content, reasoning, rawCalls, usage.isEmpty() ? null : usage);
    }

    // ── Internal ───────────────────────────────────────────

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        return new LinkedHashMap<>();
    }

    private static List<Map<String, Object>> listOfMaps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map) {
                    out.add((Map<String, Object>) item);
                }
            }
        }
        return out;
    }

    private static String strOf(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? 0 : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, Object> choiceMessage(Map<String, Object> data) {
        Object choices = data.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> m && m.get("message") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> message = (Map<String, Object>) m.get("message");
                return message;
            }
        }
        return new LinkedHashMap<>();
    }

    /**
     * Send a POST request, automatically retrying on failure (rate limit / timeout / 5xx etc. recoverable errors).
     *
     * <p>Two failure classes are <b>not</b> retried: exhausted-account errors (HTTP 402 or an error
     * body mentioning insufficient balance/quota — the auto-pause hook fires immediately so the
     * whole system stops instead of hammering an empty account) and requests sent while the system
     * is paused (the pause gate aborts them so a paused simulation makes no further API calls).
     *
     * @return the response JSON Map; returns null when giving up (reason in retryError).
     */
    protected Map<String, Object> postWithRetry(URI url, Map<String, Object> payload) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(apiTimeoutSeconds))
                .build();
        String lastErr = "";
        for (int attempt = 1; attempt <= retryMax; attempt++) {
            // System pause: never send (or keep retrying) a request while paused — the caller
            // receives "[API error: aborted: system paused]" and holds until resume.
            if (isSystemPaused()) {
                retryError = "aborted: system paused";
                logger.warn("{} API request aborted: system paused (attempt {}/{})",
                        apiName, attempt, retryMax);
                return null;
            }
            HttpRequest.Builder rb = HttpRequest.newBuilder(url)
                    .timeout(Duration.ofSeconds(apiTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload)));
            if (apiKey != null && !apiKey.isEmpty()) {
                rb.header("Authorization", "Bearer " + apiKey);
            }
            HttpRequest request = rb.build();
            try {
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                String respBody = resp.body();
                // Exhausted account balance/quota: unrecoverable until the account is topped up —
                // notify the auto-pause hook and give up immediately (402 never recovers, and some
                // backends report quota exhaustion as 429, which the generic branch would retry forever).
                if (isInsufficientBalance(status, respBody)) {
                    retryError = "HTTP " + status + ": " + truncate(respBody, 200);
                    logger.error("{} API request failed — account balance/quota insufficient, "
                            + "auto-pausing: {}", apiName, retryError);
                    notifyInsufficientBalance(status);
                    return null;
                }
                if (status == 429 || status >= 500) {
                    lastErr = "HTTP " + status;
                    logger.warn("{} API request failed ({}, attempt {}/{}), retrying in {}s",
                            apiName, lastErr, attempt, retryMax, (long) retryDelay);
                    sleep();
                    continue;
                }
                if (status >= 400) {
                    retryError = "HTTP " + status + ": " + truncate(respBody, 200);
                    logger.error("{} API request failed, unrecoverable: {}", apiName, retryError);
                    return null;
                }
                return parseBody(respBody);
            } catch (java.net.http.HttpTimeoutException e) {
                lastErr = "timeout";
                logger.warn("{} API request timed out (attempt {}/{}), retrying in {}s", apiName, attempt, retryMax, (long) retryDelay);
                sleep();
            } catch (Exception e) {
                lastErr = e.getClass().getSimpleName() + ": " + truncate(e.getMessage(), 120);
                logger.warn("{} API request error ({}, attempt {}/{}), retrying in {}s",
                        apiName, lastErr, attempt, retryMax, (long) retryDelay);
                sleep();
            }
        }
        retryError = "Retried " + retryMax + " times and still failed: " + lastErr;
        logger.error("{} API request failed {} times, giving up: {}", apiName, retryMax, lastErr);
        return null;
    }

    /** Wait {@link #retryDelay} between attempts, waking early (and aborting the retry) when the system pauses. */
    private void sleep() {
        long end = System.currentTimeMillis() + (long) (retryDelay * 1000);
        while (System.currentTimeMillis() < end) {
            if (isSystemPaused()) {
                return;  // the attempt loop re-checks the pause gate and aborts without another request
            }
            long remain = end - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(200L, remain));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static Map<String, Object> parseBody(String body) throws java.io.IOException {
        return Json.parseObject(body);
    }

    /** Core API call. Returns (contentText, usageMap, reasoningText). */
    protected Object[] callApi(List<Map<String, Object>> messages, double temperature, int maxTokens) {
        URI url = URI.create(baseUrl + "/v1/chat/completions");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        debug("{} API call: model={} messages={}", apiName, model, messages.size());

        Map<String, Object> data = postWithRetry(url, payload);
        if (data == null) {
            return new Object[]{"[API error: " + retryError + "]", null, ""};
        }
        Map<String, Object> message = choiceMessage(data);
        String content = strOf(message.get("content"));
        Object reasoningObj = message.get("reasoning_content");
        if (reasoningObj == null) {
            reasoningObj = message.get("reasoning");
        }
        String reasoning = strOf(reasoningObj);
        if (!reasoning.isEmpty()) {
            debug("{} reasoning ({} chars)", apiName, reasoning.length());
        }
        if (content.isEmpty() && !reasoning.isEmpty()) {
            logger.warn("{}: empty content, falling back to reasoning_content", apiName);
            content = reasoning;
        }
        Map<String, Object> usage = mapOf(data.get("usage"));
        if (!usage.isEmpty()) {
            debug("{} tokens: prompt={} completion={} total={}", apiName,
                    usage.getOrDefault("prompt_tokens", "?"),
                    usage.getOrDefault("completion_tokens", "?"),
                    usage.getOrDefault("total_tokens", "?"));
        }
        if (content.isEmpty()) {
            logger.warn("{} returned empty content.", apiName);
        }
        return new Object[]{content, usage.isEmpty() ? null : usage, reasoning};
    }
}
