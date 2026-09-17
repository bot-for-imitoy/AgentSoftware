package com.agent.software.llm;

import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 供应商目录：从 classpath 的 providers.default.json 加载，并叠加可选的本地覆盖文件。
 *
 * <p>本地覆盖文件路径优先级（先命中先用）：
 * <ol>
 *   <li>环境变量 {@code AGENTSOFTWARE_PROVIDERS_FILE}；</li>
 *   <li>同名 {@code -D} 系统属性 {@code AGENTSOFTWARE_PROVIDERS_FILE}；</li>
 *   <li>{@code AppPaths.configFile("providers.local.json")}（文件不存在则跳过）。</li>
 * </ol>
 *
 * <p>合并规则：本地文件按 {@code id} 覆盖内置项（逐字段合并，未给出的字段沿用内置值），
 * 内置没有的 id 追加到末尾；{@code "api_keys": {providerId: key}} 单独记下，
 * 供 {@link ProviderResolver} 取 key（{@link #apiKeyOverride(String)}）。
 *
 * <p>任何解析失败都只记 warn 并降级（内置目录失败 → 空列表；本地文件失败 → 只用内置），
 * 绝不让整个程序起不来。
 */
public final class ProviderCatalog {

    private static final Logger logger = LoggerFactory.getLogger(ProviderCatalog.class);

    /** 内置目录的 classpath 资源。 */
    private static final String DEFAULT_RESOURCE = "/providers.default.json";

    /** 指向本地覆盖文件的路径环境变量 / 同名系统属性。 */
    private static final String LOCAL_FILE_ENV = "AGENTSOFTWARE_PROVIDERS_FILE";

    /** 配置目录下的本地覆盖文件名。 */
    private static final String LOCAL_FILE_NAME = "providers.local.json";

    /** 目录解析只需一个线程安全的 ObjectMapper（配置好后即可并发读）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<ProviderDef> providers;
    private final Map<String, ProviderDef> byId;
    private final Map<String, String> localApiKeys;

    /**
     * 隐式无参构造器：字段初始化时完成"内置目录 + 本地覆盖"的一次性加载。
     */
    {
        Loaded loaded = load();
        this.providers = List.copyOf(loaded.providers());
        Map<String, ProviderDef> index = new LinkedHashMap<>();
        for (ProviderDef def : this.providers) {
            index.put(def.id(), def);
        }
        this.byId = Map.copyOf(index);
        this.localApiKeys = Map.copyOf(loaded.apiKeys());
    }

    /** 全部供应商定义（本地覆盖合并后的结果）。 */
    public List<ProviderDef> all() {
        return providers;
    }

    /** 按 id 查找供应商。 */
    public Optional<ProviderDef> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /** 本地覆盖文件 {@code api_keys} 里为某供应商登记的 key（非空白才算命中）。 */
    public Optional<String> apiKeyOverride(String providerId) {
        if (providerId == null) {
            return Optional.empty();
        }
        String key = localApiKeys.get(providerId);
        return key == null || key.isBlank() ? Optional.empty() : Optional.of(key);
    }

    /** 一个供应商的静态定义。 */
    public record ProviderDef(String id, String name, String baseUrl, String apiKeyEnv,
                              String defaultModel, boolean enabled) {
    }

    // ── 加载 ────────────────────────────────────────────────────

    /** 一次加载的产物：合并后的定义列表 + 本地 api_keys。 */
    private record Loaded(List<ProviderDef> providers, Map<String, String> apiKeys) {
    }

    private static Loaded load() {
        // 1. 内置目录：用 raw JSON map 保留"未出现字段"的信息，便于逐字段合并。
        Map<String, Map<String, Object>> merged = readDefaultEntries();
        if (merged == null) {
            logger.warn("内置供应商目录不可用，供应商列表为空");
            return new Loaded(List.of(), Map.of());
        }
        // 2. 可选本地覆盖：解析失败只跳过该文件，不拖垮内置目录。
        Map<String, String> apiKeys = new LinkedHashMap<>();
        Path local = localOverridePath();
        if (local != null && Files.exists(local)) {
            try {
                Map<String, Object> root = readJson(Files.readString(local, StandardCharsets.UTF_8));
                mergeLocal(merged, root);
                apiKeys.putAll(readApiKeys(root));
                logger.debug("已叠加本地供应商覆盖文件: {}", local);
            } catch (Exception e) {
                logger.warn("本地供应商覆盖文件解析失败，忽略该文件: {} ({})", local, e.getMessage());
            }
        }
        List<ProviderDef> defs = new ArrayList<>();
        for (Map<String, Object> raw : merged.values()) {
            defs.add(toDef(raw));
        }
        return new Loaded(defs, apiKeys);
    }

    /** 读内置资源并按 id 建索引；失败返回 null（由调用方降级为空列表）。 */
    private static Map<String, Map<String, Object>> readDefaultEntries() {
        try (InputStream in = ProviderCatalog.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                logger.warn("classpath 缺少内置供应商目录 {}", DEFAULT_RESOURCE);
                return null;
            }
            return indexProviders(readJson(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            logger.warn("内置供应商目录 {} 解析失败: {}", DEFAULT_RESOURCE, e.getMessage());
            return null;
        }
    }

    /** 取根对象里的 {@code "providers"} 数组，按 id 建有序索引。 */
    private static Map<String, Map<String, Object>> indexProviders(Map<String, Object> root) {
        Object list = root.get("providers");
        if (!(list instanceof List<?> entries)) {
            throw new IllegalArgumentException("供应商目录缺少 'providers' 数组");
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Object item : entries) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> entry = asStringMap(map);
            String id = str(entry.get("id"));
            if (id.isEmpty()) {
                logger.warn("供应商目录里有一项缺少 id，已跳过");
                continue;
            }
            out.put(id, entry);
        }
        return out;
    }

    /** 把本地文件的 providers 逐字段合并进内置索引；新 id 追加到末尾。 */
    private static void mergeLocal(Map<String, Map<String, Object>> merged, Map<String, Object> root) {
        Object list = root.get("providers");
        if (!(list instanceof List<?> entries)) {
            return;
        }
        for (Object item : entries) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> entry = asStringMap(map);
            String id = str(entry.get("id"));
            if (id.isEmpty()) {
                logger.warn("本地供应商覆盖文件里有一项缺少 id，已跳过");
                continue;
            }
            Map<String, Object> existing = merged.get(id);
            // 覆盖已有 id：保留原位置；新 id：追加（LinkedHashMap 的 put 语义）。
            merged.put(id, existing == null ? new LinkedHashMap<>(entry) : deepMerge(existing, entry));
        }
    }

    /** 由合并后的 raw map 装配 ProviderDef；enabled 缺省 true。 */
    private static ProviderDef toDef(Map<String, Object> raw) {
        String id = str(raw.get("id"));
        String name = str(raw.get("name"));
        if (name.isEmpty()) {
            name = id;
        }
        return new ProviderDef(id, name, str(raw.get("base_url")),
                nullable(str(raw.get("api_key_env"))), nullable(str(raw.get("default_model"))),
                boolOr(raw.get("enabled"), true));
    }

    /** 定位本地覆盖文件；任何路径解析异常都退化为"没有本地文件"。 */
    private static Path localOverridePath() {
        String fromEnv = System.getenv(LOCAL_FILE_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv.trim());
        }
        String fromProp = System.getProperty(LOCAL_FILE_ENV);
        if (fromProp != null && !fromProp.isBlank()) {
            return Path.of(fromProp.trim());
        }
        try {
            return AppPaths.resolve(AppConfig.defaults().storage()).configFile(LOCAL_FILE_NAME);
        } catch (Exception e) {
            logger.debug("无法解析本地供应商覆盖文件路径: {}", e.getMessage());
            return null;
        }
    }

    /** 读 {@code "api_keys": {providerId: key}}；空白值忽略。 */
    private static Map<String, String> readApiKeys(Map<String, Object> root) {
        Map<String, String> out = new LinkedHashMap<>();
        Object keys = root.get("api_keys");
        if (keys instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                String value = String.valueOf(e.getValue());
                if (!value.isBlank()) {
                    out.put(String.valueOf(e.getKey()), value);
                }
            }
        }
        return out;
    }

    /** 递归合并：b 覆盖 a；map 逐键合并，其余值直接替换，显式 null 表示删除该字段。 */
    private static Map<String, Object> deepMerge(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> out = new LinkedHashMap<>(a);
        for (Map.Entry<String, Object> e : b.entrySet()) {
            Object bv = e.getValue();
            Object av = out.get(e.getKey());
            if (av instanceof Map && bv instanceof Map) {
                out.put(e.getKey(), deepMerge(asStringMap((Map<?, ?>) av), asStringMap((Map<?, ?>) bv)));
            } else if (bv == null) {
                out.remove(e.getKey());
            } else {
                out.put(e.getKey(), bv);
            }
        }
        return out;
    }

    private static Map<String, Object> readJson(String text) throws Exception {
        Map<String, Object> root = MAPPER.readValue(text, new TypeReference<Map<String, Object>>() {
        });
        return root == null ? Map.of() : root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String nullable(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static boolean boolOr(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        return switch (String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> fallback;
        };
    }
}
