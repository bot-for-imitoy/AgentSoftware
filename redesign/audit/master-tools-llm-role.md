# Structured Inventory — `master` (`llm`, `role`, `tools`)

Scope: `.master-view/src/main/java/com/agent/software/{llm,role/RoleLoader,role/RoleFactory,tools}`.
Read-only checkout; all statements from the named files only.

## 1. `llm`

### 1.1 `LLM` (interface, 83 lines)
Public contract for one LLM client; the doc calls it "the public interface of the Python `llm.py`".
- `String LLM_ERROR_MARKERS = "[API error:"` — literal prefix produced by this package when a call fails; consumers detect failure by string match.
- `final class ChatResponse` — `text`, `reasoning` (chain-of-thought; `""` when absent), `tokens`.

  - `ChatResponse(String text, int tokens)` / `ChatResponse(String text, String reasoning, int tokens)` — immutable value object; nulls normalized to `""`.
- `final class ToolsResponse` — `content`, `reasoning`, `toolCalls` (never null), `usage` (nullable).

  - `ToolsResponse(content, toolCalls, usage)` / `ToolsResponse(content, reasoning, toolCalls, usage)` — value object.
  - `int totalTokens()` — reads `usage.total_tokens`, `0` when `usage` is null or not a `Number`.
- `ChatResponse chat(String system, String user, double temperature, Integer maxTokens)` — single-shot chat; `system` may be null/empty (skipped).
- `ChatResponse summarize(String logText, double temperature, Integer maxTokens)` — summarize a work log.
- `ToolsResponse chatWithTools(List<Map<String,Object>> messages, List<Map<String,Object>> tools, double temperature, Integer maxTokens)` — native function calling (OpenAI shape).
- Thread-safety: interface only; no state.

### 1.2 `OpenAICompatLLM` (587 lines)
Sole `LLM` implementation; one instance per role (per RetryArbiter doc). Speaks only the OpenAI wire protocol (`POST {baseUrl}/v1/chat/completions`, `Authorization: Bearer`).
- Config constants: `API_KEY_ENV="OPENAI_API_KEY"`, `BASE_URL_ENV="OPENAI_BASE_URL"`, `MODEL_ENV="OPENAI_MODEL"`, `DEFAULT_BASE_URL="https://api.openai.com"`, `DEFAULT_MODEL="gpt-4o-mini"`.
- Public mutable retry knobs: `double retryDelay = 10.0`; `int retryMax = 200`; `int apiTimeoutSeconds = 120`; `String apiName = "OpenAI"`; `label` (role prefix for DEBUG logs).
- `OpenAICompatLLM()` — full layered resolution.
- `OpenAICompatLLM(apiKey, baseUrl, model, label, configStore)` — explicit overrides win; `label` is the log prefix.
- package-private `OpenAICompatLLM(..., Map<String,String> env, Map<String,String> props)` — test injection: non-null maps replace real env/props entirely.
- Config layers (high→low), per private `resolve(...)`: explicit ctor arg → system property named like the env var (`-DOPENAI_API_KEY=...`) → environment variable → `ConfigStore` dotted path (`llm.api_key` / `llm.base_url` / `llm.model`) → default value. `baseUrl` trailing `/` stripped (`stripSlash`).
- `void setOnInsufficientBalance(Consumer<String>)` — auto-pause hook; fired once on HTTP 402 or an "insufficient balance/quota" body match (English + Chinese markers in `INSUFFICIENT_BALANCE_MARKERS`).
- `void setPauseGate(Supplier<Boolean>)` — while true, no request is sent/retried and queued requests abort; caller gets `[API error: aborted: system paused]`.
- `RetryArbiter retryArbiter()` / `void setRetryArbiter(RetryArbiter)` — endpoint arbiter (lazily re-created if null).
- `ChatResponse chat(...)` — default `maxTokens 512`; delegates to `callApi`; token count from `usage.total_tokens`.
- `ChatResponse summarize(...)` — hard-coded English summarizer system prompt; default `maxTokens 256`.
- `ToolsResponse chatWithTools(...)` — builds payload (`model`, `messages`, `temperature`, `tools`, `tool_choice="auto"`, optional `max_tokens`); returns `ToolsResponse("[API error: "+retryError+"]", List.of(), null)` on give-up; falls back to `reasoning_content`/`reasoning` when `content` is empty; normalizes `tool_calls` to a list of maps.
- `protected Object[] callApi(messages, temperature, maxTokens)` — returns `(content, usage, reasoning)`; failure text `"[API error: " + retryError + "]"`.
- `protected Map<String,Object> postWithRetry(URI, payload)` — one fresh `HttpClient` per call; loop `attempt = 1..retryMax`:
  - pause gate checked before every attempt (abort, no request);
  - acquires a `RetryArbiter.Slot` with `retries = attempt-1`; a null slot means aborted (paused/interrupted);
  - `isInsufficientBalance` → set `retryError = "HTTP <status>: <body≤200>"`, fire hook, give up (not retried);
  - `429` / `>=500` → `arbiter.throttled("HTTP <status>")`, warn, sleep `retryDelay`, retry;
  - other `>=400` → `retryError = "HTTP <status>: <body≤200>"`, give up;
  - 2xx → parse JSON, `succeeded = true` (this lifts congestion);
  - `HttpTimeoutException` → `lastErr="timeout"`; other exceptions → `SimpleClassName: message≤120`; both retried;
  - `slot.release(succeeded)` in `finally`, i.e. **before** the backoff sleep;
  - after the loop: `retryError = "Retried <retryMax> times and still failed: <lastErr>"`.
- `sleep()` — interruptible `retryDelay` wait in ≤200 ms slices; returns early if the pause gate trips (the loop then aborts).
- Thread-safety: per-instance mutable fields (`retryError`, hooks) are not synchronized; each role owns one instance. `RetryArbiter` is the shared synchronization point.

### 1.3 `RetryArbiter` (377 lines, `final`)
Priority gate that orders concurrent attempts of one endpoint while it throttles us.
- `static RetryArbiter forEndpoint(String endpointKey)` — shared instance per key, normally Base URL (`null`/empty → `"<default>"`); backed by `static final Map<String,RetryArbiter> SHARED` (ConcurrentHashMap, never evicted).
- `static void clearShared()` — test isolation only.
- `static final long POLL_MILLIS = 200L` — abort-condition re-check interval.
- `volatile int maxConcurrentWhileCongested = 1` — concurrent attempts allowed while congested.
- `final class Slot implements AutoCloseable` — one granted attempt.
  - `long waitedMillis()` — queue wait of this attempt (diagnostics).
  - `void release(boolean succeeded)` — idempotent; a success with an empty queue and no active attempt lifts congestion.
  - `void close()` → `release(false)`.
- `Slot acquire(int retries, long pollMillis, BooleanSupplier keepWaiting)`:
  - not congested → immediate slot, no cap, no queue;
  - congested → park in `waiting`, sorted by `PRIORITY` (retry count DESC, then arrival ASC); re-check `keepWaiting` every `pollMillis`; on abort/interrupt remove self, `maybeAdmit()`, return `null`.
- `void throttled(String cause)` — marks endpoint congested (logs only on transition).
- `void release(boolean)` / private `maybeAdmit()` — grants slots to highest-retry waiters up to `maxConcurrentWhileCongested`; `waiting.clear()` fallback branch if not congested.
- Diagnostics: `isCongested()`, `waitingCount()`, `activeCount()`, `highestWaitingRetries()` (`-1` when empty), `name()`, `long[] stats()` = `{parkedTotal, admittedTotal, throttledTotal}`, `toString()`.
- Thread-safety: `ReentrantLock` + `Condition` deliberately (virtual threads; avoids pinning); all mutable state guarded by the lock. Global sharing keyed by Base URL means one arbiter spans all roles and all `AgentSystem` instances in the JVM.

### 1.4 `provider/Provider` (265 lines, immutable)
One provider API description, parsed from a catalog JSON entry.
- `enum ApiFormat { OPENAI, ANTHROPIC }`
  - `static ApiFormat parse(String, String providerId)` — throws `IllegalArgumentException` for null/unknown (`supported: openai, anthropic`).
  - `defaultChatCompletionsPath()` → `/chat/completions` | `/messages`.
  - `defaultAuthHeader()` → `Authorization` | `x-api-key`.
  - `defaultAuthScheme()` → `Bearer` | null (raw key).
- `static final String DEFAULT_MODELS_PATH = "/models"`.
- `static Provider fromJson(Map<String,Object> entry, String source)` — requires `id` and `base_url`; `api_format` required; derives paths/auth defaults; nulls normalized; throws `IllegalArgumentException` with the source file name.
- Getters: `id()`, `name()`, `apiFormat()`, `baseUrl()` (no trailing `/`), `modelsPath()`, `chatCompletionsPath()`, `apiKeyEnv()` (null = no key needed), `authHeader()`, `authScheme()`, `headers()` (immutable copy), `defaultModel()`, `website()`, `enabled()`, `description()`.
- `boolean requiresApiKey()` — `apiKeyEnv != null`.
- `String modelsUrl()` / `chatCompletionsUrl()` — base + path join.
- Thread-safety: all fields final, `Map.copyOf(headers)`.

### 1.5 `provider/ProviderManager` (647 lines)
Knows the provider catalog and fetches/normalizes each provider's `GET /models`.
- Catalog sources (low→high): bundled classpath `/providers.default.json`; optional local providers file (same schema) merged field-by-field by provider `id`, can add providers, override fields, `enabled:false`, and hold secrets in `api_keys`.
- Local-file discovery (`discoverLocalConfigPath`): system property `llm.providers.config` → env `LLM_PROVIDERS_CONFIG` → `PathManager.createDefault().configDir()/providers.json`.
- `static ProviderManager loadDefaults()`; `static ProviderManager load()` (missing local file is not an error); `static ProviderManager load(Path)` / `load(String)` (missing/invalid file → `ProviderException`).
- `List<Provider> all()` (catalog order, includes disabled); `List<Provider> enabled()`; `Optional<Provider> find(String)`; `Provider require(String)` (unknown → `ProviderException` listing known ids).
- `void setApiKey(String providerId, String)`; `Optional<String> explicitApiKey(String)`; `String resolveApiKey(Provider)` — order: explicit/local `api_keys` → `System.getenv(apiKeyEnv)` → `System.getProperty(apiKeyEnv)`; null when unset.
- `List<ModelInfo> listModels(String|Provider)` — cached; `refreshModels(String)` bypasses cache; `findModel(providerId, modelId)`; `long getCacheTtlMillis()/setCacheTtlMillis(long)` (default 5 min, 0 disables); `int get/setRequestTimeoutSeconds(int)` (default 30, min 1); `void clearCache()`.
- `checkEnabled` — disabled provider → `ProviderException`; missing required key → message naming the env var and alternatives.
- `requestModels(Provider)` — GET with per-provider static headers + auth (`scheme + " " + key` or raw), `Accept`, `User-Agent: AgentSoftware-ProviderManager/1.0`; non-2xx → `ProviderException(providerId, status, ...)`; `InterruptedException` re-interrupts.
- `parseModels` — expects `data` JSON array; per entry: `id` (skip empty), `display_name`/`name`; timestamp by dialect: ANTHROPIC `created_at` ISO-8601 (`Instant` then `OffsetDateTime`), OPENAI `created` epoch seconds (tolerates millis); `owned_by`.
- Merge internals: `parseCatalogRoot`, `toJsonMaps` (round-trips `Provider` back to raw maps so merging has one representation), `deepMerge` (nested maps recurse; explicit JSON null removes a field), `readApiKeys`.
- `static final class CacheEntry` — expiry + `List.copyOf` snapshot.
- Thread-safety: `providers`/`byId` immutable after construction; `apiKeys` and `modelsCache` are `ConcurrentHashMap`; `cacheTtlMillis`/`requestTimeoutSeconds` are plain mutable fields.

### 1.6 `provider/ModelInfo` (63 lines, immutable)
Normalized model entry: `id()`, `displayName()` (falls back to id), `createdAtMillis()` (nullable), `ownedBy()` (`""` when absent), `raw()` (immutable copy of the original JSON entry).

### 1.7 `provider/ProviderException` (39 lines, checked)
- `ProviderException(String providerId, int statusCode, String message)`; `(String providerId, String message, Throwable cause)` (status `-1`).
- `String providerId()`; `int statusCode()` (HTTP status or `-1`); `static ProviderException config(String)` — convenience for configuration errors not tied to a provider.

## 2. `role`

### 2.1 `RoleLoader` (386 lines, `final`, all-static)
Uniform loader: every role comes from the classpath resource `role_templates.json` (`DEFAULT_TEMPLATES_RESOURCE`, no leading slash); 55 built-in templates; the JSON registry is "the only data source".
- Registry: `public static final Map<String,Supplier<AgentRole>> TEMPLATES = new LinkedHashMap<>()`; populated at class-load from the resource; failure → `ExceptionInInitializerError`.
- JSON shape: top-level `role_id → role config` map (shape ①), or `[{...}]` array (shape ②), or a single role object containing `role_id` (shape ③). Fields: required `role_id`, `name`; documented `title`, `responsibilities`, `personality`, `skills[]`, `interest_keywords[]`, `system_prompt_extra`, `is_default`, `group`, `email`, `username`, `uid`, `computer_kind`, `computer_kwargs`, `state` (`AgentState` name, e.g. `ON_DUTY_IDLE`), `salience_threshold` (>0 applied).
- `fromJsonMap(Map)` — each call builds an independent `AgentRole` via `AgentRole.builder()`; missing `name` falls back to `role_id`; `username`/`uid` derived (pinyin username / container uid) when omitted; unknown/absent optional fields keep `AgentRole` defaults.
- `toJsonMap(AgentRole)` — inverse; omits default-valued fields (`username`/`uid` when empty/0, `is_default` false, `email`, `computer_kind` when `"podman"`, `state` when `ON_DUTY_IDLE`, `salience_threshold` when `0.4`); `toJsonString` pretty-prints.
- `templatesFromJson(String)` → `Map<String,Supplier<AgentRole>>` (does not touch `TEMPLATES`); shape ① uses the **outer key as authoritative `role_id`** (overwrites any inner value).
- `loadFromJson(String)` / `loadFromJson(Path)` → independent `List<AgentRole>`.
- `registerFromJson(String)` → merges into global `TEMPLATES`, returns the count.
- `registryToJsonString()` — whole registry as pretty JSON (used to regenerate the resource).
- `addTemplate(AgentRole)` — registers a deep-copied builder/factory (used by `RoleFactory` for hiring).
- `Supplier<AgentRole> makeRole(name, roleId, title, responsibilities, personality, skills, keywords, extra, group)` — programmatic factory with the same copy semantics.
- Defaults/lookup: `DEFAULT_ROLES` = CEO, COO, HR, CTO, business_analyst, frontend/backend/fullstack/mobile/test leads, architect, release_manager, dev/ tester_1..20 / attacker_1..3 (CFO deliberately not in the default set); `createAllRoles()`; `createDefaultRoles()`; `getTemplate(String)` (unknown → `IllegalArgumentException` listing available keys).
- Name pool: private `NAME_POOL` (24 Chinese names) + `usedNames`; `static synchronized String nextName()` seeds `usedNames` from all templates on first call, then first free pool name, else `"Employee NNN"`.
- Thread-safety: `TEMPLATES` is a plain `LinkedHashMap` mutated unsynchronized by `addTemplate`/`registerFromJson`; only `nextName()` is `synchronized`; class-load writes are safely published by class initialization.

### 2.2 `RoleFactory` (187 lines)
LLM-driven role creation (port of `role_factory.py`); each instance owns one `LLM`.
- `RoleFactory(String apiKey, String model)` — creates `new OpenAICompatLLM(apiKey, null, model, "role_factory", null)` (base URL from layered config).
- `RoleFactory()` — `(null, null)`.
- `AgentRole createRole(String requirement)`:
  1. serializes every `RoleLoader.TEMPLATES` entry (role_id/title/first≤5 skills/sorted first≤5 keywords) into the `CREATE_ROLE_PROMPT` template;
  2. calls `llm.chat(system="…HR specialist…output only JSON", prompt, 0.3, 512)`;
  3. `parseJson(resp.text)` (candidates: direct `Json.parse` → ```json fenced block → first `{...}`); null → `IllegalArgumentException("Failed to parse role config from LLM response: …")`;
  4. requires fields `role_id, title, responsibilities, personality, skills, interest_keywords`; missing → `IllegalArgumentException`;
  5. `RoleLoader.nextName()` for the person name; de-duplicates `role_id` by appending `_1`, `_2`, …
  6. builds `AgentRole` via `AgentRole.builder()` and **registers it into the global pool** with `RoleLoader.addTemplate(role)`;
  7. returns the new `AgentRole`; logs role_id, name, title, skill/keyword counts, token usage.
- `static Map<String,Object> parseJson(String)` — package-private, three-strategy JSON extraction; warns and returns null on failure.
- What it binds: only the static `AgentRole` template registry — hiring does not bind stores, computers, toolkits, or a pool. Onboarding (`RolePool.addRoleAndStart`) is the caller's job (see `hr/PostJobPosting`).
- Thread-safety: no synchronization; concurrent `createRole` calls race on `nextName()` (safe) and on `TEMPLATES` (unsafe) and can pick colliding `role_id`s.

## 3. `tools`

### 3.1 `Tool` template contract (78 lines, abstract)
- `abstract String getToolName()` — function name exposed to the LLM.
- `abstract Map<String,Object> getSchema()` — flat `paramName → description (String)`, or `paramName → full property description Map` to declare types/enums.
- `Map<String,Object> getInputSchema()` — converts to OpenAI shape `{type:"object", properties:{...}}`; String values become `{"type":"string","description":...}`, Map values pass through unchanged. No `required` array is ever emitted.
- `abstract String handler(Map<String,Object> args)` — validates args and returns the result text handed back to the LLM.
- `String getDescription()` — default `"Tool <name>: params a(desc), b(desc)"` derived from the schema (toolkits rarely override it; most override the toolkit description instead).

### 3.2 `Toolkit` template contract (68 lines, abstract)
- `protected final ArrayList<Tool> tools`; `protected void addTool(Tool)`; `ArrayList<Tool> getTools()` returns the live mutable list; ctors `Toolkit()` / `Toolkit(ArrayList<Tool>)`.
- `String trigger(String toolName, Map<String,Object> arg)` — linear scan dispatching to the first matching tool handler; returns `null` when not found (no error text, no arg coercion).
- `static String snakeCase(String)` — CamelCase→snake_case, used by `getName()`; `getName()` = snake_case of the class simple name; `getDescription()` = `"Toolkit <name> (<n> tools)"`.

### 3.3 Assembly per role — `Toolkits` (90 lines, `final`, private ctor)
- Static owners: `MCPManager MCP_MANAGER = new MCPManager()` and `SkillManager SKILL_MANAGER = new SkillManager()` (process-level fallbacks); `DEFAULT_MCP_GROUPS = ["file_ops"]`; `LEADERSHIP_GROUP = "Leadership Group"`; accessors `getMcpManager()` / `getSkillManager()`.
- `static List<Toolkit> defaultToolkits(AgentRole role)` returns **new instances** in order: `Memory`, `Note`, `Time`, `Todo`, `TaskView`, `Pc`, `McpManager(role, role.mcpManager())`, `Skill(role, role.skillManager())`, `Email(role, role.mailService())`; plus `Client(role)` **only** when `role.group` equals `"Leadership Group"`. If `role == null`, `role.mcpManager()`/`skillManager()`/`mailService()` are avoided by ternaries but `new Memory(role)`/`new Note(role)`/… still dereference the null role (NPE).
- Wiring owners per the javadoc: `RolePool.setupRole` iterates `defaultToolkits(role)` and calls `AgentRole.addToolkit(Toolkit)`, which registers tools into the `ToolRegistry` exposed to the LLM.
- Not in the default list: `Hermes` (documented "disabled by default"; no instantiation exists outside the toolkit package), `Hr` (added explicitly in `Main.java:232` for the HR role), `Talk` (added by `AgentRole` itself, `AgentRole.java:787`, with the role's `RolePool`), `Client` also added manually in `demo/WebDemo.java`.
- Optional per-toolkit injection: every toolkit keeps a convenience constructor taking `AgentRole` only, so tests/standalone callers can build one without a system; those constructors fall back to new/global manager instances.

### 3.4 Toolkit / tool inventory

| Toolkit class → name | Tool | What it does | Collaborators required |
|---|---|---|---|
| `Memory` → `memory` | `summary` | Saves today's work summary for a day; marks role `OFF_DUTY`, closes the day conversation, powers off the computer. | `NoteStore`, `AgentRole` (`timeManager()`, `state`, `setState()`, `journal()`, `conversation()`, `computerIfCreated()`), `Computer` |
| `Note` → `note` | `write_note` | Creates/overwrites a note with optional reminder tick/day. | `NoteStore` |
| | `edit_note` | Overwrites existing note content; optional reminder reset. | `NoteStore` (`editNote`, `getReminder`) |
| | `list_notes` | Lists note titles with reminder info. | `NoteStore` |
| | `read_note` | Returns the note body. | `NoteStore` |
| | `delete_note` | Deletes note and its reminder. | `NoteStore` |
| `Time` → `time` | `get_time` | Returns simulated calendar/clock description + current tick. | `TimeEventBus` (`describe()`, `currentTick()`) |
| | `take_rest` | Sets role state to `ON_DUTY_IDLE` (rest). | `AgentRole` (`state`, `setState()`) |
| `Todo` → `todo` | `todo_add` | Adds a todo item, returns its id. | `TodoStore` |
| | `todo_list` | Lists own todos, optional status filter. | `TodoStore` |
| | `todo_update` | Changes a todo status (`pending`/`in_progress`/`completed`). | `TodoStore` |
| | `todo_delete` | Deletes a todo item. | `TodoStore` |
| `TaskView` → `task_view` | `my_tasks` | Shows pending task queue + last 10 done/failed tasks with tokens; scope filter. | `AgentRole` (`pendingTasks()`, `taskHistory()`, `Task` fields) |
| `Pc` → `pc` | `run_command` | Runs a shell command on the role's personal computer (60 s / 2000-char limits). | `Computer` |
| | `computer_status` | Describes the computer (on/off, workdir, type). | `Computer` |
| | `lan_devices` | Lists LAN peers (person, role_id, computer, IP). | `ComputerManager` (role's, else `getInstance()`) |
| | `reboot` | Powers the computer off then on. | `Computer` |
| `McpManager` → `mcp_manager` | `mcp_search` | Keyword search over MCP tools installed on the role's computer. | `AgentRole.computer()`, `ToolRegistry.ToolDef` |
| | `mcp_list` | Lists all MCP tools of the role's computer. | `AgentRole.computer()` |
| | `mcp_add` | Registers an MCP tool as a role tool (proxy handler → `runMcpTool`). | `AgentRole` (`computer()`, `addSingleTool`), `MCPManager` |
| | `mcp_remove` | Uninstalls an MCP tool from computer + role tool registry. | `AgentRole` (`computer()`, `removeSingleTool`), `MCPManager` |
| | `mcp_my_tools` | Lists MCP tools already added to this role. | `MCPManager`, `AgentRole` |
| `Skill` → `skill` | `skill_search` | Keyword search over the SKILL.md library. | `SkillManager` |
| | `skill_list` | Lists all library skills (name + description). | `SkillManager` |
| | `skill_add` | Adds a skill as a role tool returning full SKILL.md text on call. | `AgentRole` (`addSingleTool`, `roleId`), `SkillManager` |
| | `skill_remove` | Removes a previously added skill tool. | `AgentRole` (`removeSingleTool`), `SkillManager` |
| | `skill_my_skills` | Lists skills added to this role. | `SkillManager`, `AgentRole` |
| `Email` → `email` | `send_email` | Sends company mail to names/addresses (comma-separated), with cc and failure notes. | `AgentRole` (`pool()`, `name`, `journal()`), `MailService`, `RolePool` |
| | `read_mail` | Inbox preview list, limit + unread-only. | `AgentRole`, `MailService` |
| | `open_mail` | Full mail text by id, marks read. | `AgentRole`, `MailService` |
| | `mail_address_book` | Company roster by group with e-mails and titles. | `AgentRole.pool()`, `RolePool.allRoles()`, `MailService` |
| `Hermes` → `hermes` | `hermes_new_conversation` | Runs `hermes chat` on the computer and parses the resume id from output. | `Computer.runCommand` |
| | `hermes_send` | Sends content to a Hermes conversation and returns the reply (600 s timeout, shell-quoted). | `Computer.runCommand` |
| `Talk` → `talk` | `talk` | Intra-group message/delegation; wait=false enqueues a task, wait=true blocks for reply; enforces same-group and detects wait cycles; optional cloud-drive attachment. | `AgentRole` (`group`, `state`, `journal()`, `computer()`, `addTask`, `talkWait`, `waitingReplyFrom`, `deliverReply`, `queueDepth`), `RolePool`, `ChatStore`, `Computer`, `Types.AgentState` |
| | `list_roles` | Prints the team roster (name, responsibility, group, ≤4 skills). | `RolePool.allRoles()` |
| `Hr` → `hr` | `post_job_posting` | LLM-creates a role from a requirement, registers it in `TEMPLATES`, onboards it via `RolePool.addRoleAndStart`. | `AgentRole.pool()`, `RoleFactory` (its own LLM/API key), `RolePool` |
| | `list_candidates` | Lists every role template (role_id, name, title, skill count). | `RoleLoader.TEMPLATES` (global static) |
| `Client` → `client` | `talk_to_client` | Takes the client-interaction mutex, records the question to the chat store, reads a reply via the system's `Input` (console or Web), returns it. | `AgentRole` (`roleId`, `name`, `group`, `system()`), `ClientCommunicationLock`, `ChatStore`, `Input`/`StdInput` |

Support (not toolkits):
- `mcp/MCPManager` — `static loadRules()` (`/mcp_group_rules.json`), `static matchGroup(name, patterns)` (glob), `installGroupDefaults(role, group)` (ensures MCP servers on the role computer, filters by group rules, adds each tool), `addTool(role, toolName)` / `removeTool(role, toolName)` (mutates `roleTools` + role tool registry), `listRoleTools(role)`; state `Map<String,Set<String>> roleTools` keyed by roleId, unsynchronized, never cleaned.
- `skill/SkillManager` — `SkillInfo` (name/description/path, `toolName()` slug, `readSkillMd()`, `listRelatedFiles()`); `ensureLoaded()` scans `data/skills` (or injected dir) for `SKILL.md`, parses frontmatter `name`/`description`, de-duplicates names with `-2`, `-3`; `listAvailable()`, `searchSkills(kw)`, `addSkill(role, name)` / `removeSkill(role, name)` / `listRoleSkills(role)`; state `skills` + `roleSkills` plain `LinkedHashMap`, `loaded` flag, unsynchronized.
- `client/ClientCommunicationLock` — process singleton (`getInstance()`) plus public ctor for per-system instances; `synchronized tryAcquire(roleId, name)` (re-entrant per role, returns conflict description otherwise), `release(roleId)` (holder-only, idempotent), `isHeld()`, `holderRoleId()`, `holderName()`, `holderDescription()`.

## 4. Cross-cutting couplings / problems observed

1. **Toolkits reach straight into `AgentRole` internals.** Public fields (`role.state`, `role.group`, `role.name`, `role.roleId`, `role.title`, `role.responsibilities`, `role.skills`, `role.interestKeywords`) and a wide internal API (`journal`, `conversation`, `computerIfCreated`, `addSingleTool`/`removeSingleTool`, `addTask`, `deliverReply`, `talkWait`, `waitingReplyFrom`, `queueDepth`, `pendingTasks`, `taskHistory`, `system().chatStore/input/clientLock`) are used directly; `Summary` and `TakeRest` mutate role state and power off hardware from inside a tool handler.
2. **Manager ownership is ambiguous.** `Toolkits.defaultToolkits` prefers `role.mcpManager()`/`role.skillManager()` but falls back to the static singletons; meanwhile `new McpManager(role)` / `new Skill(role)` build *fresh* `MCPManager`/`SkillManager` instances. Because `SkillManager` and `MCPManager` keep their per-role bookkeeping (`roleSkills`, `roleTools`) as instance state, which instance is used decides what `skill_my_skills` / `mcp_my_tools` report, and the singleton fallback silently mixes roles across systems.
3. **`defaultToolkits(null)` NPEs.** Only `MCPManager`/`SkillManager`/`Email` guard against a null role; `new Memory(role)` (and Note/Time/Todo/TaskView/Pc) call `role.noteStore()` etc. in their `AgentRole` constructors.
4. **Duplicated schema and text.** The `reminder_tick` description, `toInt` numeric coercion, `oBool` parsing, `truncate`, `errorHint`, `PodmanQuote`, and "Error: needs …" message shapes are copy-pasted across toolkit classes; `HermesSend`/`HermesNewConversation` also disagree on the command shape (`chat -q … -r` vs parsing `hermes --resume`).
5. **Stringly-typed args and inconsistent validation.** Every handler open-codes `instanceof String` checks; almost all schemas declare `type: string` (no `required`, no integer/boolean types emitted by `Tool.getInputSchema`), yet handlers expect `Integer` (`WriteNote` rejects non-`Integer` `reminder_tick`) while `EditNote`/`ReadMail` accept numeric strings — the same LLM behaviour succeeds in one tool and errors in another.
6. **Mixed error conventions.** Tool results are plain strings: `"<tool>: Error: …"` in most handlers, `"Error: …"` without the tool prefix in `WriteNote`'s first branch, `"Success: …"`/`"Error: …"` in `MCPManager`/`SkillManager`, and bare text in `TaskView`/`list_roles`. Failures are detected by string prefix (`startsWith("Error")`, `Types.isFailureText`, `LLM_ERROR_MARKERS`, `out.startsWith("[exit")`), so wording changes silently change behaviour.
7. **Global mutable static state.** `RoleLoader.TEMPLATES` (read/written by `RoleFactory`, `PostJobPosting`, `ListCandidates`), `Toolkits.MCP_MANAGER`/`SKILL_MANAGER`, `RetryArbiter.SHARED` (never evicted), and the `ClientCommunicationLock`/`ComputerManager`/`MailService` singletons make roles non-isolated across `AgentSystem` instances in one JVM.
8. **LLM config layering is inconsistent with provider config.** `OpenAICompatLLM.resolve` checks system property before environment variable; `ProviderManager.resolveApiKey` checks environment before system property; the two paths also read different files/stores (`ConfigStore.llm.*` vs `providers.json`/`api_keys`).
9. **Retry policy is hard-coded, not configured.** `retryDelay=10`, `retryMax=200`, `apiTimeoutSeconds=120` are public mutable fields set in code; a fresh `HttpClient` is built per `postWithRetry` call; congestion is global per Base URL, so a slow endpoint serializes every role through one arbiter slot (`maxConcurrentWhileCongested=1`).
10. **`Provider.apiFormat=ANTHROPIC` has no chat client.** Only `/models` normalizes the Anthropic dialect; `OpenAICompatLLM` always posts to `{baseUrl}/v1/chat/completions` with a `Bearer` header, so Anthropic providers are catalog-only.
11. **Toolkit registry is class-name-driven.** `Toolkit.getName()` derives the tool-name prefix from the class simple name, so renaming a class silently changes the toolkit identity, and `Hr`/`Talk`/`Hermes` live outside `defaultToolkits` (wired in `Main`, `AgentRole`, or not at all) — the "default set" in the class javadoc does not match the code paths that actually register them.
12. **Per-role mutable tool state in managers is unsynchronized and unclean.** `MCPManager.roleTools` / `SkillManager.roleSkills`/`skills` are plain `LinkedHashMap`s mutated from role worker threads and keyed by `roleId` with no removal on offboarding.
