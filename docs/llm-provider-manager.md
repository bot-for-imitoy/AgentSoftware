# LLM Provider Manager

A small, dependency-light manager for LLM **provider APIs**: it knows which
providers exist (from a bundled default catalog plus an optional local
providers file) and can fetch each provider's **model list** through its
`GET /models` endpoint, normalizing the OpenAI and Anthropic response dialects
into one `ModelInfo` shape.

Package: `com.agent.software.llm.provider`

## Files

| File | Purpose |
| --- | --- |
| `src/main/resources/providers.default.json` | Bundled default catalog of mainstream providers (OpenAI, Anthropic, Gemini, DeepSeek, Mistral, Groq, OpenRouter, Together, xAI, Kimi/Moonshot, Zhipu GLM, Qwen/DashScope, SiliconFlow, Cerebras, NVIDIA NIM, plus local Ollama/vLLM/LM Studio) — each with base URL, request paths and API format. |
| `src/main/resources/providers.local.example.json` | Example local providers file (new providers, field overrides, disabling, `api_keys`). |
| `ProviderManager.java` | The manager class (loading/merging, API-key resolution, `/models` fetching with cache). |
| `Provider.java` | One provider definition (immutable; parsed from a catalog entry). |
| `ModelInfo.java` | One normalized model entry of a `/models` response. |
| `ProviderException.java` | Checked error of the manager (unknown provider, missing key, HTTP/parse failure). |
| `src/test/java/com/agent/software/llm/provider/ProviderManagerTest.java` | JUnit tests against a stub HTTP server. |

## Provider catalog schema

Both the default file and the local file use the same JSON schema; the two
supported API formats are `"openai"` and `"anthropic"`.

```jsonc
{
  "id": "openai",                  // stable id: the merge key and lookup key
  "name": "OpenAI",                // display name
  "api_format": "openai",          // "openai" | "anthropic"  (required)
  "base_url": "https://api.openai.com/v1",   // API root (required)
  "models_path": "/models",        // optional, default "/models"
  "chat_completions_path": "/chat/completions", // optional, default per format
  "api_key_env": "OPENAI_API_KEY", // optional env var holding the key (also -D)
  "auth_header": "Authorization",  // optional, derived from api_format by default
  "auth_scheme": "Bearer",         // optional, derived from api_format by default
  "headers": { "anthropic-version": "2023-06-01" },  // optional static headers
  "default_model": "gpt-4o-mini",  // optional hint only
  "website": "https://...",        // optional documentation URL
  "enabled": true                  // optional, default true
}
```

Format defaults: **openai** → `Authorization: Bearer <key>`, chat path
`/chat/completions`; **anthropic** → `x-api-key: <key>` (raw), chat path
`/messages`. Anthropic additionally requires the static
`anthropic-version` header, which its catalog entry supplies via `headers`.

## Local providers file (merge rules)

`ProviderManager.load()` (or `load(path)`) merges the local file over the
defaults by provider `id`:

- new ids are **appended**;
- existing ids are merged **field by field** (nested maps merge recursively;
  an explicit JSON `null` removes the field);
- `"enabled": false` disables a provider (it stays visible via
  `find(id)`/`all()` but is excluded from `enabled()` and from model fetching);
- the top-level `api_keys` map (`{"openai": "sk-..."}`) registers keys without
  touching the committed default catalog.

The local file is located by the first hit among:

1. the explicit path passed to `load(Path)`;
2. the `llm.providers.config` system property / `LLM_PROVIDERS_CONFIG`
   environment variable;
3. `providers.json` under the platform config directory
   (`~/.config/AgentSoftware/providers.json` on Linux).

## API keys

Resolution order for one provider (first hit wins):

1. `manager.setApiKey("openai", "sk-...")`;
2. the local file's `api_keys` map;
3. the environment variable named by `api_key_env` (e.g. `OPENAI_API_KEY`);
4. a `-D` system property of the same name.

Local servers (Ollama/vLLM/LM Studio) have no `api_key_env` and need no key.

## Usage

```java
import com.agent.software.llm.provider.*;

ProviderManager manager = ProviderManager.load();      // defaults + local file

// 1) Which provider APIs exist?
for (Provider p : manager.enabled()) {
    System.out.println(p.id() + " -> " + p.baseUrl()
            + " [" + p.apiFormat() + "] models=" + p.modelsUrl());
}

// 2) Model list through each provider's GET /models endpoint
List<ModelInfo> models = manager.listModels("openai");  // cached, TTL 5 min
for (ModelInfo m : models) {
    System.out.println(m.id() + " | " + m.displayName() + " | " + m.ownedBy());
}

// 3) One model's info (queries /models, cached)
Optional<ModelInfo> gpt4 = manager.findModel("openai", "gpt-4o-mini");

// Force refresh / drop cache
manager.refreshModels("deepseek");
manager.clearCache();
```

Add a local override (e.g. point DeepSeek at a company gateway and keep the key
out of git):

```json
{
  "api_keys": { "deepseek": "sk-..." },
  "providers": [
    { "id": "deepseek", "base_url": "https://llm-gateway.example.com/v1" }
  ]
}
```

## Design notes

- Only the OpenAI and Anthropic dialects are modeled (`api_format`); Google
  Gemini and every OpenAI-compatible vendor are reached through their
  OpenAI-compatible endpoints.
- Providers whose model-list endpoint deviates from the plain
  `GET {base_url}/models` shape (e.g. Azure OpenAI with its `api-version`
  query/`api-key` header, Amazon Bedrock with SigV4 signing) are deliberately
  not shipped in the default catalog — define them in the local file with
  `auth_header`/`auth_scheme` overrides when needed.
- Errors (unknown/disabled provider, missing key, HTTP error, malformed
  response) surface as checked `ProviderException` with `providerId` and
  `statusCode`.
