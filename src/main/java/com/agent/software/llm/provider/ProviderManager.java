package com.agent.software.llm.provider;

import com.agent.software.store.PathManager;
import com.agent.software.utils.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM Provider manager: knows which provider APIs exist and fetches their model
 * catalogs through each provider's {@code GET /models} endpoint.
 *
 * <p><b>Provider catalog sources</b> (low → high priority):
 * <ol>
 *   <li>the bundled default catalog, classpath resource
 *       {@code /providers.default.json} (mainstream providers: OpenAI, Anthropic,
 *       DeepSeek, Groq, ... each with base URL, request paths and API format —
 *       OpenAI or Anthropic);</li>
 *   <li>an optional <em>local providers file</em>, a JSON document with the very
 *       same schema, which can add brand-new providers, override any field of an
 *       existing provider (field-level merge, keyed by provider {@code id}), and
 *       disable providers ({@code "enabled": false}). Its {@code api_keys} map
 *       keeps secrets out of the committed catalog.</li>
 * </ol>
 * The local file is located (first hit wins):
 * <ol>
 *   <li>the explicit path given to {@link #load(Path)};</li>
 *   <li>the {@code llm.providers.config} system property / {@code LLM_PROVIDERS_CONFIG}
 *       environment variable;</li>
 *   <li>{@code providers.json} under the platform config directory
 *       ({@link PathManager#configDir()}, e.g. {@code ~/.config/AgentSoftware/}).</li>
 * </ol>
 *
 * <p><b>API key resolution</b> for one provider (first hit wins): a key registered
 * through {@link #setApiKey}, the local file's {@code api_keys} map, the environment
 * variable named by {@code api_key_env} (e.g. {@code OPENAI_API_KEY}), and finally a
 * {@code -D} system property of the same name.
 *
 * <p><b>Model catalogs</b>: {@link #listModels(String)} calls
 * {@code GET {base_url}{models_path}} with the auth/static headers the provider's
 * API format requires and normalizes the response (OpenAI or Anthropic dialect)
 * into a list of {@link ModelInfo}. Results are cached (default TTL 5 minutes);
 * {@link #refreshModels(String)} bypasses the cache and {@link #clearCache()} drops it.
 *
 * <p>Usage:
 * <pre>{@code
 * ProviderManager manager = ProviderManager.load();          // defaults + local file
 * for (Provider p : manager.enabled()) {
 *     List<ModelInfo> models = manager.listModels(p.id());   // GET /models
 *     Optional<ModelInfo> gpt4 = manager.findModel("openai", "gpt-4o-mini");
 * }
 * }</pre>
 */
public class ProviderManager {

    private static final Logger logger = LoggerFactory.getLogger(ProviderManager.class);

    /** Classpath resource of the bundled default provider catalog. */
    public static final String DEFAULT_PROVIDERS_RESOURCE = "/providers.default.json";

    /** System property ({@code llm.providers.config}) pointing at the local providers file. */
    public static final String LOCAL_CONFIG_SYS_PROP = "llm.providers.config";

    /** Environment variable ({@code LLM_PROVIDERS_CONFIG}) pointing at the local providers file. */
    public static final String LOCAL_CONFIG_ENV = "LLM_PROVIDERS_CONFIG";

    /** File name of the local providers file under the platform config directory. */
    public static final String LOCAL_CONFIG_FILE_NAME = "providers.json";

    private static final String PROVIDERS_KEY = "providers";
    private static final String API_KEYS_KEY = "api_keys";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final long DEFAULT_CACHE_TTL_MILLIS = 5 * 60_000L;

    private final List<Provider> providers;
    private final Map<String, Provider> byId = new LinkedHashMap<>();
    private final Map<String, String> apiKeys = new ConcurrentHashMap<>();

    // Model catalog cache: provider id -> (expiresAtMillis, immutable model list)
    private final Map<String, CacheEntry> modelsCache = new ConcurrentHashMap<>();
    private long cacheTtlMillis = DEFAULT_CACHE_TTL_MILLIS;
    private int requestTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    // ── Construction ─────────────────────────────────────────

    private ProviderManager(List<Provider> providers, Map<String, String> apiKeys) {
        this.providers = List.copyOf(providers);
        for (Provider p : providers) {
            this.byId.put(p.id(), p);
        }
        if (apiKeys != null) {
            for (Map.Entry<String, String> e : apiKeys.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    this.apiKeys.put(e.getKey(), e.getValue());
                }
            }
        }
    }

    /** Load only the bundled default catalog (no local overrides). */
    public static ProviderManager loadDefaults() {
        return new ProviderManager(parseCatalogRoot(readClasspathResource()), null);
    }

    /**
     * Load the default catalog plus, when present, the local providers file at the
     * discovered location (system property, environment variable, then
     * {@code ~/.config/AgentSoftware/providers.json}). A missing local file is not
     * an error — defaults alone are returned.
     *
     * @throws ProviderException when the discovered local file exists but cannot be parsed
     */
    public static ProviderManager load() throws ProviderException {
        Path local = discoverLocalConfigPath();
        if (local != null && Files.exists(local)) {
            return load(local);
        }
        if (local != null) {
            logger.debug("Local providers file {} not found, using defaults only", local);
        }
        return loadDefaults();
    }

    /**
     * Load the default catalog merged with the given local providers file.
     *
     * @param localConfig path of the local providers JSON file
     * @throws ProviderException when the file is missing or not valid JSON
     */
    public static ProviderManager load(Path localConfig) throws ProviderException {
        if (localConfig == null || !Files.exists(localConfig)) {
            throw new ProviderException("", -1, "Local providers file not found: " + localConfig);
        }
        Map<String, Object> localRoot = parseFile(localConfig);
        return new ProviderManager(mergeCatalogs(parseCatalogRoot(readClasspathResource()), localRoot),
                readApiKeys(localRoot));
    }

    /** Load with an explicit local path and return a config error as ProviderException. */
    public static ProviderManager load(String localConfigPath) throws ProviderException {
        return load(Path.of(localConfigPath));
    }

    private static Path discoverLocalConfigPath() {
        String sysProp = System.getProperty(LOCAL_CONFIG_SYS_PROP);
        if (sysProp != null && !sysProp.isEmpty()) {
            return Path.of(sysProp);
        }
        String env = System.getenv(LOCAL_CONFIG_ENV);
        if (env != null && !env.isEmpty()) {
            return Path.of(env);
        }
        try {
            return PathManager.createDefault().configDir().resolve(LOCAL_CONFIG_FILE_NAME);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Provider lookup ──────────────────────────────────────

    /** All known providers (including disabled ones), in catalog order. */
    public List<Provider> all() {
        return providers;
    }

    /** Providers with {@code enabled: true}, in catalog order. */
    public List<Provider> enabled() {
        List<Provider> out = new ArrayList<>();
        for (Provider p : providers) {
            if (p.enabled()) {
                out.add(p);
            }
        }
        return out;
    }

    /** Find a provider by id (disabled providers are found as well). */
    public Optional<Provider> find(String providerId) {
        return Optional.ofNullable(byId.get(providerId));
    }

    /** Get a provider by id or throw a descriptive error. */
    public Provider require(String providerId) throws ProviderException {
        Provider p = byId.get(providerId);
        if (p == null) {
            throw new ProviderException(providerId, -1,
                    "Unknown provider '" + providerId + "'. Known: " + knownIds());
        }
        return p;
    }

    private String knownIds() {
        List<String> ids = new ArrayList<>();
        for (Provider p : providers) {
            ids.add(p.id());
        }
        return String.join(", ", ids);
    }

    // ── API keys ─────────────────────────────────────────────

    /** Register an API key for a provider (highest resolution priority). */
    public void setApiKey(String providerId, String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            apiKeys.remove(providerId);
        } else {
            apiKeys.put(providerId, apiKey);
        }
    }

    /** Explicitly registered key for the provider (from setApiKey / local api_keys), if any. */
    public Optional<String> explicitApiKey(String providerId) {
        return Optional.ofNullable(apiKeys.get(providerId));
    }

    /**
     * Resolve the API key of a provider. Resolution order: {@link #setApiKey} /
     * local {@code api_keys} → environment variable {@code api_key_env} → system
     * property of the same name. Returns null when nothing is configured.
     */
    public String resolveApiKey(Provider provider) {
        String key = apiKeys.get(provider.id());
        if (key != null && !key.isEmpty()) {
            return key;
        }
        String envName = provider.apiKeyEnv();
        if (envName == null || envName.isEmpty()) {
            return null;
        }
        String fromEnv = System.getenv(envName);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        String fromProp = System.getProperty(envName);
        return (fromProp == null || fromProp.isEmpty()) ? null : fromProp;
    }

    /** API key required but not configured → error message naming the env variable. */
    private String missingKeyMessage(Provider p) {
        return "No API key configured for provider '" + p.id() + "'. "
                + "Set the environment variable " + p.apiKeyEnv()
                + " (or a -D system property of the same name), add an entry to the "
                + "'api_keys' map of the local providers file, or call setApiKey(\""
                + p.id() + "\", \"sk-...\").";
    }

    // ── Model list (GET /models) ─────────────────────────────

    /**
     * Fetch the model list of a provider through its {@code GET /models} endpoint,
     * served from the cache when fresh.
     *
     * @throws ProviderException unknown/disabled provider, missing API key, HTTP error or bad response
     */
    public List<ModelInfo> listModels(String providerId) throws ProviderException {
        Provider p = require(providerId);
        checkEnabled(p);
        CacheEntry cached = modelsCache.get(p.id());
        long now = System.currentTimeMillis();
        if (cached != null && now < cached.expiresAtMillis) {
            return cached.models;
        }
        return refreshModels(p);
    }

    /** Fetch the model list of a provider object (cached like {@link #listModels(String)}). */
    public List<ModelInfo> listModels(Provider provider) throws ProviderException {
        return listModels(provider.id());
    }

    /** Bypass the cache and query the provider's /models endpoint right now. */
    public List<ModelInfo> refreshModels(String providerId) throws ProviderException {
        Provider p = require(providerId);
        checkEnabled(p);
        return refreshModels(p);
    }

    private List<ModelInfo> refreshModels(Provider p) throws ProviderException {
        List<ModelInfo> models = requestModels(p);
        modelsCache.put(p.id(), new CacheEntry(System.currentTimeMillis() + cacheTtlMillis, models));
        logger.info("Provider '{}': fetched {} models from {}", p.id(), models.size(), p.modelsUrl());
        return models;
    }

    /**
     * Find one model of a provider by id (queries the /models endpoint, cached).
     *
     * @return empty when the provider is reachable but does not list that model id
     */
    public Optional<ModelInfo> findModel(String providerId, String modelId) throws ProviderException {
        for (ModelInfo m : listModels(providerId)) {
            if (m.id().equals(modelId)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** Model catalog cache TTL in millis (0 disables caching). */
    public long getCacheTtlMillis() {
        return cacheTtlMillis;
    }

    public void setCacheTtlMillis(long cacheTtlMillis) {
        this.cacheTtlMillis = Math.max(0, cacheTtlMillis);
    }

    /** Drop every cached model list. */
    public void clearCache() {
        modelsCache.clear();
    }

    /** Request timeout for /models calls in seconds. */
    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
    }

    private void checkEnabled(Provider p) throws ProviderException {
        if (!p.enabled()) {
            throw new ProviderException(p.id(), -1,
                    "Provider '" + p.id() + "' is disabled (enabled: false); "
                            + "re-enable it in the local providers file to query its models");
        }
    }

    // ── HTTP ─────────────────────────────────────────────────

    private List<ModelInfo> requestModels(Provider p) throws ProviderException {
        String key = resolveApiKey(p);
        if (p.requiresApiKey() && (key == null || key.isEmpty())) {
            throw new ProviderException(p.id(), -1, missingKeyMessage(p));
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(p.modelsUrl()))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Accept", "application/json")
                .header("User-Agent", "AgentSoftware-ProviderManager/1.0");
        for (Map.Entry<String, String> h : p.headers().entrySet()) {
            rb.header(h.getKey(), h.getValue());
        }
        if (key != null && !key.isEmpty()) {
            String value = p.authScheme() == null ? key : p.authScheme() + " " + key;
            rb.header(p.authHeader(), value);
        }
        HttpRequest request = rb.GET().build();
        logger.debug("Provider '{}': GET {} (api format {})", p.id(), p.modelsUrl(), p.apiFormat());

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new ProviderException(p.id(), status,
                        "Model list request to " + p.modelsUrl() + " failed with HTTP "
                                + status + ": " + truncate(response.body(), 200));
            }
            return parseModels(p, response.body());
        } catch (ProviderException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ProviderException(p.id(), "Model list request to " + p.modelsUrl()
                    + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private static List<ModelInfo> parseModels(Provider p, String body) throws ProviderException {
        Map<String, Object> root;
        try {
            root = Json.parseObject(body);
        } catch (IOException e) {
            throw new ProviderException(p.id(), "Provider '" + p.id()
                    + "' returned invalid JSON from " + p.modelsUrl() + ": "
                    + truncate(body, 200), e);
        }
        Object data = root.get("data");
        if (!(data instanceof List<?> entries)) {
            throw new ProviderException(p.id(), -1, "Provider '" + p.id() + "' response from "
                    + p.modelsUrl() + " has no JSON array under 'data' (unexpected model list shape): "
                    + truncate(body, 200));
        }
        List<ModelInfo> out = new ArrayList<>();
        for (Object item : entries) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) m;
            String id = Json.str(entry, "id", "");
            if (id.isEmpty()) {
                continue;
            }
            String displayName = Json.str(entry, "display_name",
                    Json.str(entry, "name", id));
            Long createdMillis = switch (p.apiFormat()) {
                case ANTHROPIC -> parseIsoTimestamp(Json.str(entry, "created_at", null));
                case OPENAI -> epochSecondsToMillis(entry.get("created"));
            };
            String ownedBy = Json.str(entry, "owned_by", "");
            out.add(new ModelInfo(id, displayName, createdMillis, ownedBy, entry));
        }
        logger.debug("Provider '{}': parsed {} models (api format {})", p.id(), out.size(), p.apiFormat());
        return out;
    }

    /** OpenAI dialect: "created" is epoch seconds (tolerates millis too). */
    private static Long epochSecondsToMillis(Object created) {
        if (!(created instanceof Number n)) {
            return null;
        }
        long value = n.longValue();
        return value > 10_000_000_000L ? value : value * 1000L;
    }

    /** Anthropic dialect: "created_at" is an ISO-8601 timestamp. */
    private static Long parseIsoTimestamp(String created) {
        if (created == null || created.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(created).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(created).toInstant().toEpochMilli();
            } catch (DateTimeParseException e2) {
                logger.warn("Cannot parse model created_at timestamp '{}'", created);
                return null;
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ── Catalog parsing & merging ────────────────────────────

    /** Parse the "providers" array of a catalog root into provider objects. */
    static List<Provider> parseCatalogRoot(Map<String, Object> root) {
        String source = "provider catalog";
        Object list = root.get(PROVIDERS_KEY);
        if (!(list instanceof List<?> entries)) {
            throw new IllegalArgumentException("Provider catalog must contain a JSON array under '"
                    + PROVIDERS_KEY + "': " + source);
        }
        List<Provider> out = new ArrayList<>();
        for (Object item : entries) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("Each entry of '" + PROVIDERS_KEY
                        + "' must be a JSON object: " + source);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) m;
            out.add(Provider.fromJson(entry, source));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Provider catalog has no providers: " + source);
        }
        return out;
    }

    /** Read the classpath default catalog ({@value #DEFAULT_PROVIDERS_RESOURCE}). */
    private static Map<String, Object> readClasspathResource() {
        try (InputStream in = ProviderManager.class.getResourceAsStream(DEFAULT_PROVIDERS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled resource missing: " + DEFAULT_PROVIDERS_RESOURCE);
            }
            return Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled resource "
                    + DEFAULT_PROVIDERS_RESOURCE, e);
        }
    }

    private static Map<String, Object> parseFile(Path file) throws ProviderException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ProviderException("", "Cannot read local providers file " + file, e);
        }
        try {
            Object root = Json.parse(text);
            if (!(root instanceof Map)) {
                throw new ProviderException("", -1,
                        "Local providers file must be a JSON object: " + file);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> rootMap = (Map<String, Object>) root;
            return rootMap;
        } catch (IOException e) {
            throw new ProviderException("",
                    "Local providers file is not valid JSON: " + file + " (" + e.getMessage() + ")", e);
        }
    }

    /** Read the optional "api_keys": {providerId: key} map of a local catalog file. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> readApiKeys(Map<String, Object> localRoot) {
        Map<String, String> out = new LinkedHashMap<>();
        Object keys = localRoot.get(API_KEYS_KEY);
        if (keys instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
        return out;
    }

    /**
     * Merge the default catalog with the local one. Providers of the local file
     * that already exist (same id) get their fields merged field-by-field over the
     * default entry (nested maps merge recursively); new ids are appended at the
     * end. Disabling happens per provider entry ({@code enabled: false}).
     */
    private static List<Provider> mergeCatalogs(List<Provider> defaults, Map<String, Object> localRoot) {
        // Rebuild defaults as raw JSON maps so the field-level merge works on one representation.
        List<Map<String, Object>> defaultMaps = toJsonMaps(defaults);
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (Map<String, Object> m : defaultMaps) {
            String id = Json.str(m, "id", "");
            merged.put(id, m);
            order.add(id);
        }
        Object localList = localRoot.get(PROVIDERS_KEY);
        if (localList instanceof List<?> entries) {
            for (Object item : entries) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) m;
                String id = Json.str(entry, "id", "");
                if (id.isEmpty()) {
                    throw new IllegalArgumentException("Local providers entry without 'id'");
                }
                Map<String, Object> existing = merged.get(id);
                if (existing == null) {
                    merged.put(id, new LinkedHashMap<>(entry));
                    order.add(id);
                } else {
                    merged.put(id, deepMerge(existing, entry));
                }
            }
        }
        List<Provider> out = new ArrayList<>();
        for (String id : order) {
            out.add(Provider.fromJson(merged.get(id), "merged provider catalog"));
        }
        return out;
    }

    /** Convert parsed providers back to plain JSON maps (for uniform merging). */
    private static List<Map<String, Object>> toJsonMaps(List<Provider> providers) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Provider p : providers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("name", p.name());
            m.put("api_format", p.apiFormat() == Provider.ApiFormat.OPENAI ? "openai" : "anthropic");
            m.put("base_url", p.baseUrl());
            m.put("models_path", p.modelsPath());
            m.put("chat_completions_path", p.chatCompletionsPath());
            if (p.apiKeyEnv() != null) {
                m.put("api_key_env", p.apiKeyEnv());
            }
            m.put("auth_header", p.authHeader());
            m.put("auth_scheme", p.authScheme());
            m.put("headers", p.headers());
            if (p.defaultModel() != null) {
                m.put("default_model", p.defaultModel());
            }
            if (p.website() != null) {
                m.put("website", p.website());
            }
            m.put("enabled", p.enabled());
            if (!p.description().isEmpty()) {
                m.put("description", p.description());
            }
            out.add(m);
        }
        return out;
    }

    /** Recursive merge: b wins over a; maps merge key-wise, other values are replaced. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> out = new LinkedHashMap<>(a);
        for (Map.Entry<String, Object> e : b.entrySet()) {
            Object bv = e.getValue();
            Object av = out.get(e.getKey());
            if (av instanceof Map && bv instanceof Map) {
                out.put(e.getKey(), deepMerge((Map<String, Object>) av, (Map<String, Object>) bv));
            } else if (bv == null) {
                out.remove(e.getKey()); // explicit null in local config removes the field
            } else {
                out.put(e.getKey(), bv);
            }
        }
        return out;
    }

    /** Cache entry: model list snapshot plus expiry. */
    private static final class CacheEntry {
        final long expiresAtMillis;
        final List<ModelInfo> models;

        CacheEntry(long expiresAtMillis, List<ModelInfo> models) {
            this.expiresAtMillis = expiresAtMillis;
            this.models = List.copyOf(models);
        }
    }
}
