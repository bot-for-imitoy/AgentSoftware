package com.agent.software.llm.provider;

import com.agent.software.utils.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable description of one LLM provider API.
 *
 * <p>It is parsed from one entry of the provider catalog JSON (the bundled
 * {@code /providers.default.json} resource or a local providers file, see
 * {@link ProviderManager}). Both fields directly present in the JSON entry and
 * fields derived from {@link ApiFormat} defaults (auth header, request paths)
 * are exposed as getters.
 */
public final class Provider {

    /**
     * The wire format a provider speaks. Only two dialects are modeled,
     * matching the {@code api_format} values of the catalog files:
     * <ul>
     *   <li>{@link #OPENAI} — {@code Authorization: Bearer <key>} plus
     *       {@code /chat/completions} style endpoints ({@code /v1} of OpenAI and
     *       of all OpenAI-compatible vendors).</li>
     *   <li>{@link #ANTHROPIC} — {@code x-api-key: <key>} plus the
     *       {@code anthropic-version} header and {@code /messages} style
     *       endpoints.</li>
     * </ul>
     */
    public enum ApiFormat {
        OPENAI,
        ANTHROPIC;

        /** Parse the JSON value of {@code api_format}; throws for unknown values. */
        public static ApiFormat parse(String value, String providerId) {
            if (value == null) {
                throw new IllegalArgumentException("provider '" + providerId
                        + "': missing required field 'api_format' (expected 'openai' or 'anthropic')");
            }
            return switch (value.trim().toLowerCase()) {
                case "openai" -> OPENAI;
                case "anthropic" -> ANTHROPIC;
                default -> throw new IllegalArgumentException("provider '" + providerId
                        + "': unsupported 'api_format' '" + value
                        + "' (supported: openai, anthropic)");
            };
        }

        /** Default chat/completions path of this wire format. */
        public String defaultChatCompletionsPath() {
            return this == ANTHROPIC ? "/messages" : "/chat/completions";
        }

        /** Default name of the request header that carries the API key. */
        public String defaultAuthHeader() {
            return this == ANTHROPIC ? "x-api-key" : "Authorization";
        }

        /** Default auth scheme prefix (e.g. "Bearer"), or null when the key is sent raw. */
        public String defaultAuthScheme() {
            return this == ANTHROPIC ? null : "Bearer";
        }
    }

    /** The path every catalog entry uses for the model list endpoint. */
    public static final String DEFAULT_MODELS_PATH = "/models";

    private final String id;
    private final String name;
    private final ApiFormat apiFormat;
    private final String baseUrl;
    private final String modelsPath;
    private final String chatCompletionsPath;
    private final String apiKeyEnv;
    private final String authHeader;
    private final String authScheme;
    private final Map<String, String> headers;
    private final String defaultModel;
    private final String website;
    private final boolean enabled;
    private final String description;

    /** Parse one JSON provider entry. {@code source} names the file (error messages only). */
    public static Provider fromJson(Map<String, Object> entry, String source) {
        String id = Json.str(entry, "id", "");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("provider entry without 'id' in " + source);
        }
        String baseUrl = Json.str(entry, "base_url", "");
        if (baseUrl.isEmpty()) {
            throw new IllegalArgumentException("provider '" + id + "' in " + source
                    + ": missing required field 'base_url'");
        }
        ApiFormat format = ApiFormat.parse(Json.str(entry, "api_format", ""), id);
        String modelsPath = Json.str(entry, "models_path", DEFAULT_MODELS_PATH);
        String chatPath = Json.str(entry, "chat_completions_path", format.defaultChatCompletionsPath());
        String authHeader = Json.str(entry, "auth_header", format.defaultAuthHeader());
        String authScheme = entry.containsKey("auth_scheme")
                ? Json.str(entry, "auth_scheme", null)
                : format.defaultAuthScheme();
        return new Provider(id,
                Json.str(entry, "name", id),
                format,
                stripTrailingSlash(baseUrl),
                ensureLeadingSlash(modelsPath),
                ensureLeadingSlash(chatPath),
                nullable(Json.str(entry, "api_key_env", null)),
                nullable(authHeader),
                nullable(authScheme),
                parseStringMap(entry.get("headers")),
                nullable(Json.str(entry, "default_model", null)),
                nullable(Json.str(entry, "website", null)),
                Json.boolVal(entry, "enabled", true),
                Json.str(entry, "description", ""));
    }

    private Provider(String id, String name, ApiFormat apiFormat, String baseUrl,
                     String modelsPath, String chatCompletionsPath, String apiKeyEnv,
                     String authHeader, String authScheme, Map<String, String> headers,
                     String defaultModel, String website, boolean enabled, String description) {
        this.id = id;
        this.name = name;
        this.apiFormat = apiFormat;
        this.baseUrl = baseUrl;
        this.modelsPath = modelsPath;
        this.chatCompletionsPath = chatCompletionsPath;
        this.apiKeyEnv = apiKeyEnv;
        this.authHeader = authHeader;
        this.authScheme = authScheme;
        this.headers = Map.copyOf(headers);
        this.defaultModel = defaultModel;
        this.website = website;
        this.enabled = enabled;
        this.description = description;
    }

    /** Provider id, the stable key used for overrides and lookups (e.g. "deepseek"). */
    public String id() {
        return id;
    }

    /** Human readable display name (e.g. "DeepSeek"). */
    public String name() {
        return name;
    }

    /** Wire format of this provider (OpenAI or Anthropic dialect). */
    public ApiFormat apiFormat() {
        return apiFormat;
    }

    /** API root the request paths below are relative to (never ends with "/"). */
    public String baseUrl() {
        return baseUrl;
    }

    /** Path of the model list endpoint (default "/models"). */
    public String modelsPath() {
        return modelsPath;
    }

    /** Path of the chat/completions endpoint ("/chat/completions" or "/messages"). */
    public String chatCompletionsPath() {
        return chatCompletionsPath;
    }

    /**
     * Name of the environment variable that holds the API key, or null when the
     * provider needs no key (local servers). The same name is accepted as a
     * {@code -D} system property.
     */
    public String apiKeyEnv() {
        return apiKeyEnv;
    }

    /** Request header that carries the API key ("Authorization" or "x-api-key"). */
    public String authHeader() {
        return authHeader;
    }

    /** Auth scheme prefix, e.g. "Bearer"; null means the raw key is sent. */
    public String authScheme() {
        return authScheme;
    }

    /** Static request headers every call must send (e.g. anthropic-version). */
    public Map<String, String> headers() {
        return headers;
    }

    /** Suggested default model id, purely informational (may be null). */
    public String defaultModel() {
        return defaultModel;
    }

    /** Documentation URL of the provider API (may be null). */
    public String website() {
        return website;
    }

    /** Whether the provider is active. Disabled providers are kept but excluded from model fetching. */
    public boolean enabled() {
        return enabled;
    }

    /** Short human description of the provider (may be empty). */
    public String description() {
        return description;
    }

    /** Whether an API key is required to talk to this provider. */
    public boolean requiresApiKey() {
        return apiKeyEnv != null;
    }

    /** Full URL of the model list endpoint, e.g. {@code https://api.deepseek.com/v1/models}. */
    public String modelsUrl() {
        return joinUrl(baseUrl, modelsPath);
    }

    /** Full URL of the chat/completions endpoint of this provider. */
    public String chatCompletionsUrl() {
        return joinUrl(baseUrl, chatCompletionsPath);
    }

    /** Concise single-line description for logs/CLI. */
    @Override
    public String toString() {
        return "Provider{id='" + id + "', apiFormat=" + apiFormat
                + ", baseUrl='" + baseUrl + "', enabled=" + enabled + '}';
    }

    private static String joinUrl(String base, String path) {
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    private static String stripTrailingSlash(String s) {
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String ensureLeadingSlash(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String nullable(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseStringMap(Object value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
        return out;
    }
}
