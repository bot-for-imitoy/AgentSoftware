package com.agent.software.llm;

import com.agent.software.infra.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProviderCatalog} 与 {@link ProviderResolver} 的测试。
 *
 * <p>迁移自 master 的 {@code ProviderManagerTest}，但新架构把"供应商管理"拆成了
 * 只读目录（{@link ProviderCatalog}）+ 纯解析（{@link ProviderResolver}）：
 * <ul>
 *   <li>内置 {@code providers.default.json} 加载；</li>
 *   <li>本地覆盖文件的合并（逐字段覆盖 / 新增 / 禁用）与 {@code api_keys} 登记；</li>
 *   <li>{@link ProviderResolver#resolve} 的 baseUrl / apiKey / model 三级优先级；</li>
 *   <li>未知 providerId 与 null 目录/配置的兜底。</li>
 * </ul>
 *
 * <p>master 里的 {@code /models} 拉取、{@code api_format}（OpenAI/Anthropic 方言）、
 * 模型缓存、鉴权头映射与 {@code ProviderException} 在本架构里已不存在对应主代码，
 * 相关测试随之删除（见报告）。
 *
 * <p>本地覆盖文件位置用系统属性 {@code AGENTSOFTWARE_PROVIDERS_FILE} 注入；每个测试用
 * {@link TempDir} 下的独立文件，并在 finally / {@link AfterEach} 清理，避免污染其它测试。
 */
class ProviderCatalogTest {

    private static final String LOCAL_FILE_ENV = "AGENTSOFTWARE_PROVIDERS_FILE";

    /** 三级优先级里都要用到的重试配置（内容与解析无关，仅为构造 AppConfig.Llm）。 */
    private static final AppConfig.Llm.Retry RETRY = new AppConfig.Llm.Retry(3, 0.1, 5);

    @TempDir
    Path dir;

    /**
     * 默认指向一个不存在的文件：既保证"没有本地覆盖"，也绕开 {@code AppPaths} 的默认配置目录，
     * 让内置目录断言不受运行环境里可能存在的 providers.local.json 影响。
     */
    @BeforeEach
    void pointAtAbsentLocalFile() {
        System.setProperty(LOCAL_FILE_ENV, dir.resolve("absent.json").toString());
    }

    @AfterEach
    void clearLocalFileProperty() {
        System.clearProperty(LOCAL_FILE_ENV);
    }

    private Path writeLocal(String json) throws IOException {
        Path file = dir.resolve("providers.local.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private void useLocalFile(Path file) {
        System.setProperty(LOCAL_FILE_ENV, file.toString());
    }

    // ── 内置目录 ────────────────────────────────────────────

    @Test
    void testDefaultCatalogLoadsBundledProviders() {
        ProviderCatalog catalog = new ProviderCatalog();
        List<ProviderCatalog.ProviderDef> all = catalog.all();
        assertEquals(18, all.size(), "内置目录应有 18 个供应商");

        List<String> ids = all.stream().map(ProviderCatalog.ProviderDef::id).toList();
        Set<String> expected = Set.of("openai", "anthropic", "google-gemini", "deepseek",
                "mistral", "groq", "openrouter", "together", "xai", "moonshot", "zhipu",
                "dashscope", "siliconflow", "cerebras", "nvidia", "ollama", "vllm", "lm-studio");
        assertTrue(ids.containsAll(expected), "内置目录 ids: " + ids);
        assertEquals(ids.size(), Set.copyOf(ids).size(), "供应商 id 必须唯一");

        for (ProviderCatalog.ProviderDef def : all) {
            assertNotNull(def.name(), "供应商 name 不能为空: " + def.id());
            assertFalse(def.baseUrl().isBlank(), "供应商 base_url 不能为空: " + def.id());
            assertTrue(def.enabled(), "内置项默认都启用: " + def.id());
        }

        ProviderCatalog.ProviderDef openai = catalog.find("openai").orElseThrow();
        assertEquals("OpenAI", openai.name());
        assertEquals("https://api.openai.com/v1", openai.baseUrl());
        assertEquals("OPENAI_API_KEY", openai.apiKeyEnv());
        assertEquals("gpt-4o-mini", openai.defaultModel());

        ProviderCatalog.ProviderDef anthropic = catalog.find("anthropic").orElseThrow();
        assertEquals("https://api.anthropic.com/v1", anthropic.baseUrl());
        assertEquals("ANTHROPIC_API_KEY", anthropic.apiKeyEnv());
        assertEquals("claude-sonnet-4-20250514", anthropic.defaultModel());

        assertTrue(catalog.find("no-such-provider").isEmpty());
        assertTrue(catalog.find(null).isEmpty());
    }

    // ── 本地覆盖：逐字段合并 / 新增 / 禁用 / api_keys ────────

    @Test
    void testLocalFileMergesOverridesAddsAndDisables() throws IOException {
        int baseline = new ProviderCatalog().all().size();

        Path local = writeLocal("""
                {
                  "api_keys": { "openai": "sk-local-secret" },
                  "providers": [
                    { "id": "deepseek", "base_url": "https://myproxy.example.com/v1" },
                    { "id": "groq", "enabled": false },
                    { "id": "my-llm", "name": "My LLM",
                      "base_url": "http://localhost:9999/v1", "default_model": "local-model" }
                  ]
                }
                """);
        useLocalFile(local);
        ProviderCatalog catalog = new ProviderCatalog();

        // 新增一个：内置数量 + 1，且追加在末尾
        assertEquals(baseline + 1, catalog.all().size());
        assertEquals("my-llm", catalog.all().get(catalog.all().size() - 1).id());

        // 已有 provider 逐字段合并：base_url 被覆盖，其它字段沿用内置值
        ProviderCatalog.ProviderDef deepseek = catalog.find("deepseek").orElseThrow();
        assertEquals("https://myproxy.example.com/v1", deepseek.baseUrl());
        assertEquals("DEEPSEEK_API_KEY", deepseek.apiKeyEnv());
        assertEquals("deepseek-chat", deepseek.defaultModel());

        // 禁用：仍能被 find 到，但 enabled() 为 false
        ProviderCatalog.ProviderDef groq = catalog.find("groq").orElseThrow();
        assertFalse(groq.enabled());

        // 未被覆盖的内置项保持不变
        assertEquals("https://api.openai.com/v1", catalog.find("openai").orElseThrow().baseUrl());

        // 新增项：未给出的 enabled 缺省为 true
        ProviderCatalog.ProviderDef mine = catalog.find("my-llm").orElseThrow();
        assertEquals("My LLM", mine.name());
        assertEquals("http://localhost:9999/v1", mine.baseUrl());
        assertEquals("local-model", mine.defaultModel());
        assertTrue(mine.enabled());

        // 本地 api_keys
        assertEquals(Optional.of("sk-local-secret"), catalog.apiKeyOverride("openai"));
        assertEquals(Optional.empty(), catalog.apiKeyOverride("groq"), "未登记 key 的供应商没有覆盖");
        assertEquals(Optional.empty(), catalog.apiKeyOverride("no-such-provider"));
        assertEquals(Optional.empty(), catalog.apiKeyOverride(null));
    }

    /** 本地文件写坏时只记 warn 并降级为内置目录，绝不能让程序起不来。 */
    @Test
    void testBrokenLocalFileIsIgnoredAndDefaultsSurvive() throws IOException {
        int baseline = new ProviderCatalog().all().size();
        Path broken = writeLocal("{ this is not json");
        useLocalFile(broken);

        ProviderCatalog catalog = new ProviderCatalog();
        assertEquals(baseline, catalog.all().size());
        assertTrue(catalog.find("openai").isPresent());
        assertEquals(Optional.empty(), catalog.apiKeyOverride("openai"));
    }

    /** api_keys 里的空白值不算命中。 */
    @Test
    void testBlankApiKeyOverrideIsIgnored() throws IOException {
        Path local = writeLocal("""
                { "api_keys": { "openai": "   " } }
                """);
        useLocalFile(local);
        ProviderCatalog catalog = new ProviderCatalog();
        assertEquals(Optional.empty(), catalog.apiKeyOverride("openai"));
    }

    // ── ProviderResolver：baseUrl/apiKey/model 三级优先级 ────

    @Test
    void testResolvePrefersExplicitLlmConfig() {
        ProviderCatalog catalog = new ProviderCatalog();
        AppConfig.Llm llm = new AppConfig.Llm("openai", "explicit-model", "explicit-key",
                "http://explicit.example/v1", RETRY);

        ProviderResolver.Endpoint endpoint = ProviderResolver.resolve(llm, catalog);
        assertEquals("http://explicit.example/v1", endpoint.baseUrl());
        assertEquals("explicit-key", endpoint.apiKey());
        assertEquals("explicit-model", endpoint.model());
    }

    @Test
    void testResolveFallsBackToProviderDefinitionThenHardcodedDefaults() throws IOException {
        Path local = writeLocal("""
                {
                  "providers": [
                    { "id": "test-keyed", "base_url": "http://keyed.example/v1",
                      "api_key_env": "AGENTSOFTWARE_TEST_KEY_UNSET",
                      "default_model": "keyed-model" },
                    { "id": "bare" }
                  ]
                }
                """);
        useLocalFile(local);
        ProviderCatalog catalog = new ProviderCatalog();

        // llm 字段为空 → 用供应商定义里的 base_url / default_model
        ProviderResolver.Endpoint fromDef = ProviderResolver.resolve(
                new AppConfig.Llm("test-keyed", "", "", "", RETRY), catalog);
        assertEquals("http://keyed.example/v1", fromDef.baseUrl());
        assertEquals("keyed-model", fromDef.model());
        assertEquals("", fromDef.apiKey(), "没有 key 来源时为空串（本地 mock 无需鉴权）");

        // 供应商定义也没有 → 用硬编码兜底
        ProviderResolver.Endpoint fromFallback = ProviderResolver.resolve(
                new AppConfig.Llm("bare", "", "", "", RETRY), catalog);
        assertEquals("https://api.openai.com/v1", fromFallback.baseUrl());
        assertEquals("gpt-4o-mini", fromFallback.model());
        assertEquals("", fromFallback.apiKey());
    }

    /**
     * apiKey 优先级：{@code llm.apiKey()} → 本地覆盖文件的 {@code api_keys}
     * → {@code api_key_env} 命名的环境变量 / 同名 {@code -D} 系统属性 → 空串。
     * 环境变量无法在测试里设置，因此用 -D 系统属性走通最后一级。
     */
    @Test
    void testResolveApiKeyPriority() throws IOException {
        String noKeyEnv = "AGENTSOFTWARE_TEST_NOKEY_" + System.nanoTime();
        String keyedEnv = "AGENTSOFTWARE_TEST_KEYED_" + System.nanoTime();
        Path local = writeLocal("""
                {
                  "api_keys": { "test-keyed": "sk-catalog" },
                  "providers": [
                    { "id": "test-nokey", "base_url": "http://nokey.example/v1",
                      "api_key_env": "%s" },
                    { "id": "test-keyed", "base_url": "http://keyed.example/v1",
                      "api_key_env": "%s" }
                  ]
                }
                """.formatted(noKeyEnv, keyedEnv));
        useLocalFile(local);

        System.setProperty(noKeyEnv, "sk-from-property");
        System.setProperty(keyedEnv, "sk-from-property");
        try {
            ProviderCatalog catalog = new ProviderCatalog();

            // 没有本地覆盖 → 用 api_key_env 指定的系统属性
            assertEquals("sk-from-property", ProviderResolver.resolve(
                    new AppConfig.Llm("test-nokey", "", "", "", RETRY), catalog).apiKey());

            // 本地 api_keys 覆盖系统属性
            assertEquals("sk-catalog", ProviderResolver.resolve(
                    new AppConfig.Llm("test-keyed", "", "", "", RETRY), catalog).apiKey());

            // llm.apiKey() 又覆盖本地 api_keys
            assertEquals("explicit-key", ProviderResolver.resolve(
                    new AppConfig.Llm("test-keyed", "", "explicit-key", "", RETRY), catalog).apiKey());
        } finally {
            System.clearProperty(noKeyEnv);
            System.clearProperty(keyedEnv);
        }
    }

    // ── 兜底 ────────────────────────────────────────────────

    @Test
    void testUnknownProviderFallsBackToBuiltinOpenAi() {
        ProviderCatalog catalog = new ProviderCatalog();
        ProviderResolver.Endpoint endpoint = ProviderResolver.resolve(
                new AppConfig.Llm("no-such-provider", "", "", "", RETRY), catalog);
        assertEquals("https://api.openai.com/v1", endpoint.baseUrl());
        assertEquals("gpt-4o-mini", endpoint.model());
    }

    @Test
    void testNullLlmAndNullCatalogUseDefaults() {
        ProviderResolver.Endpoint endpoint = ProviderResolver.resolve(null, null);
        assertEquals("https://api.openai.com/v1", endpoint.baseUrl());
        assertEquals("gpt-4o-mini", endpoint.model());
    }

    /** 空/空白 providerId 与 null/空 catalog 都不能抛异常，走内置兜底。 */
    @Test
    void testBlankProviderIdFallsBackWithoutThrowing() {
        ProviderResolver.Endpoint blank = ProviderResolver.resolve(
                new AppConfig.Llm("", "", "", "", RETRY), null);
        assertEquals("https://api.openai.com/v1", blank.baseUrl());
        assertEquals("gpt-4o-mini", blank.model());

        ProviderResolver.Endpoint nulls = ProviderResolver.resolve(
                new AppConfig.Llm(null, null, null, null, RETRY), null);
        assertNotNull(nulls);
        assertEquals("https://api.openai.com/v1", nulls.baseUrl());
        assertEquals("gpt-4o-mini", nulls.model());
    }
}
