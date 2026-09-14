# LLM provider catalog and configuration

## Configuration precedence

LLM settings are resolved once by `ConfigLoader` with the chain
**environment variable → `config.json` → code default**:

```
llm.provider   → AGENTSOFTWARE_LLM_PROVIDER   (provider id, e.g. "deepseek")
llm.model      → AGENTSOFTWARE_LLM_MODEL      (empty = provider default model)
llm.apiKeys    → AGENTSOFTWARE_LLM_API_KEYS   (comma list of id=key pairs)
llm.retry.*    → AGENTSOFTWARE_LLM_RETRY_*    (maxAttempts/delaySeconds/timeoutSeconds)
```

`ProviderEndpointResolver` turns the selected provider plus `llm.apiKeys` into an
immutable `OpenAiCompatibleClient.Endpoint` (base URL, chat path, auth header and
scheme, extra headers, model) and the client speaks the OpenAI chat-completions
wire format, non-streaming.

## Provider catalog

`providers.default.json` is the bundled catalog (18 entries), each describing one
provider API:

| key | meaning |
|---|---|
| `id` | stable id used in `llm.provider` |
| `name` | display name |
| `api_format` | `openai` or `anthropic` |
| `base_url` | API root (already includes the version segment) |
| `models_path` | model-listing path (default `/models`) |
| `chat_completions_path` | `/chat/completions` (openai) or `/messages` (anthropic) |
| `api_key_env` | documentation of the conventional environment variable |
| `auth_header` / `auth_scheme` | `Authorization: Bearer …` or `x-api-key: …` |
| `headers` | static extra headers (e.g. `anthropic-version`) |
| `default_model` | fallback model when `llm.model` is empty |
| `enabled` / `description` / `website` | metadata |

Included providers: OpenAI, Anthropic, Google Gemini, DeepSeek, Mistral, Groq,
OpenRouter, Together, xAI, Moonshot, Zhipu, DashScope, SiliconFlow, Cerebras,
NVIDIA, Ollama, vLLM, LM Studio.

## API keys

Keys are **never** stored in the catalog. `ProviderManager.resolveApiKey` checks,
in order:

1. keys registered through `setApiKey` (or `llm.apiKeys`),
2. the provider's `api_key_env` environment variable,
3. the same name as a `-D` system property.

A provider with no `api_key_env` (local runtimes such as Ollama/vLLM/LM Studio)
needs no key.

## Local overrides

A `providers.json` in the configuration directory
(`AppPaths.configFile("providers.json")`) extends or overrides the catalog: new
ids are appended, existing ids are deep-merged (an explicit `null` removes a
field). The merge happens inside `ProviderManager`; the runtime always consumes
the resolved `Provider` objects, never raw JSON.

## Retry behaviour

`OpenAiCompatibleClient` retries 429/5xx and timeouts with a fixed delay up to
`llm.retry.maxAttempts`, other 4xx fail immediately, and an exhausted
balance/quota response triggers the auto-pause hook instead of retrying. While the
endpoint is congested, `RetryArbiter` serves attempts ordered by retry count so
the request closest to succeeding goes first, and releases the slot before the
backoff sleep.
