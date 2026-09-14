# AgentSoftware — Shift-Driven Multi-Agent Company Simulator

A multi-role LLM agent "software company" written in Java 25 (Maven, JUnit 5).
A team of LLM-powered employees — CEO, COO, HR, leads, developers, testers,
security engineers, … — works on a **simulated shift clock**, reacts to
**explicitly addressed events** instead of polling, and each employee owns a
**personal computer** (podman container, SSH host or local directory) where its
files and MCP servers live.

> Status: the module refactor (plan v2) is complete. The codebase is organised in
> strict layers, the legacy runtime/tool system has been removed, and the test
> suite covers the new architecture (`mvn test`).

---

## Design at a glance

- **Layered, inward-only dependencies.**
  `app → adapters → runtime → ports → domain → kernel`, with `tools` depending on
  `ports + domain` only. The rule is enforced by `ArchGuardTest`.
- **No global singletons.** Every collaborator is instance-scoped, so several
  independent companies can run in one JVM (`Application` is the unit of
  isolation).
- **No content-based event filtering.** An event names its recipients (empty =
  broadcast); `DeliveryPolicy` only decides whether the recipient gets it now or
  holds it because it is off duty / wrapping up / waiting.
- **Toolkits are data.** Which toolkits a role may use is declared per template in
  `role_templates.json`; there is no Java-side branching.
- **One configuration model.** `env > config.json > code default`, loaded once
  into a typed `AppConfig`.

---

## Layers and modules

| Layer | Package | Responsibility |
|---|---|---|
| kernel | `kernel` | ids (`RoleId`/`TaskId`/`EventId`/`MessageId`), `Json`, `JsonSchema`, `Names`, `FailureText`, `AgentException` |
| config | `config` | `AppConfig` (typed tree), `ConfigSource`, `ConfigLoader` (precedence), `AppPaths` (XDG/Windows/macOS roots) |
| domain | `domain` | pure model + rules: `Event`/`EventType`/`Priority`, `Task`/`TaskStatus`, `RoleSpec`, `AgentState`, `Payload`, `ShiftCalendar`, `DeliveryPolicy`, `ScheduleEntry` |
| ports | `ports` | interfaces only: `LlmPort`, `ToolPort`, `ComputerPort`, `MailPort`, `InputPort`, `TracePort`, `ClockPort`, `EventSink`, `TeamPort`, `StateRepository`, `NoteRepository`, `TodoRepository`, `SkillRepository`, plus `ToolSpec`/`ToolCall`/`ToolResult`/`ChatMessage` |
| runtime | `runtime` | orchestration: `AgentRuntime` (worker + state machine + tool loop + wait), `TaskQueue`, `ToolLoop`, `WaitCoordinator`, `TeamRuntime` (roster/hire/fire), `ClockEngine` + `ClockService`, `DispatchService`, `LifecycleCoordinator`, `LifecycleGate` |
| adapters | `adapters` | concrete ports: OpenAI-compatible HTTP client + provider catalog + `RetryArbiter`; `LegacyComputerAdapter` over podman/ssh/local; stdio `MCPServer`; file-backed repositories (state/notes/todos/skills); `MailService`; `ChatStore` + `ChatWebAdapter`; console/Web input |
| tools | `tools/spi`, `tools/builtin` | `Tool`/`Toolkit`/`Tools`/`ToolkitCatalog`/`ToolService` and the 12 built-in toolkits |
| app | `app` | composition root: `Application` (wiring + lifecycle + state + web), `RoleSpecLoader` (templates → `RoleSpec`), `RoleSpecFactory` (LLM hiring), `Main` (entry point) |
| computers | `computers` | `Computer` backends: `LocalComputer`, `PodmanComputer`, `SSHComputer`, `ComputerManager` |

---

## How a simulation runs

1. `app.Main` loads `AppConfig`, builds one `Application`, hires the default team
   (or `--roles`), restores `state.json` if present and starts.
2. `TeamRuntime.startAll()` starts one virtual thread per role, **then**
   `ClockService.start()` starts the clock — so `SHIFT_START` always has consumers.
3. The clock advances while anyone works (`simSecondsPerRealSecond`), fast-forwards
   when the whole team is idle, fires `SHIFT_START`/`SHIFT_END`/`TASK_DUE`, and
   rolls to the next day once every role is `OFF_DUTY`.
4. `DispatchService` routes each event to its recipients and converts it into a
   `Task`; `AgentRuntime` runs the LLM tool loop (`ToolLoop`) and writes traces
   through `TracePort`.
5. `summary` marks the role `OFF_DUTY`; `stop()` writes a snapshot.

Events carry `recipients` (a `Set<RoleId>`); an empty set means broadcast. The
lifecycle policy is:

| Priority | Role state | Delivery |
|---|---|---|
| `EMERGENCY` | any | immediate |
| other | `IDLE`/`BUSY` | immediate |
| other | `WAITING` | deferred until the reply arrives |
| other | `OFF_DUTY`/`WRAPPING_UP` | held until the next shift |

---

## Toolkits (declared in `role_templates.json`)

| Toolkit id | Tools |
|---|---|
| `memory` | `summary` |
| `note` | `write_note`, `read_note`, `list_notes`, `edit_note`, `delete_note` |
| `time` | `get_time`, `take_rest` |
| `todo` | `todo_add`, `todo_list`, `todo_update`, `todo_delete` |
| `task_view` | `my_tasks` |
| `pc` | `run_command`, `computer_status`, `reboot`, `lan_devices` |
| `mcp` | `mcp_search`, `mcp_list`, `mcp_add`, `mcp_remove`, `mcp_my_tools` |
| `skill` | `skill_list`, `skill_search`, `skill_add`, `skill_remove`, `skill_my_skills` |
| `email` | `send_email`, `read_mail`, `open_mail`, `mail_address_book` |
| `talk` | `talk`, `list_roles` |
| `client` | `talk_to_client` (Leadership Group) |
| `hr` | `post_job_posting`, `list_candidates` (HR) |

Every template lists its toolkits explicitly (defaults, plus `client` for
Leadership Group and `hr` for HR). Tools expose typed `JsonSchema` arguments
including `required` and enums; installing an MCP tool or a skill registers a new
tool for that role at runtime.

---

## Configuration

Precedence (highest first): **environment variable → `config.json` → code
default**. `-D` system properties are not consulted.

A dotted config path maps to an environment variable by upper-casing it,
replacing `.` with `_` and inserting `_` before camel-case humps:

```
llm.provider                  → AGENTSOFTWARE_LLM_PROVIDER
llm.apiKeys                   → AGENTSOFTWARE_LLM_API_KEYS   (k=v,k2=v2)
schedule.secondsPerTick       → AGENTSOFTWARE_SCHEDULE_SECONDS_PER_TICK
storage.dataDir               → AGENTSOFTWARE_STORAGE_DATA_DIR
web.port                      → AGENTSOFTWARE_WEB_PORT
```

`config.json` lives at `AppPaths.configFile("config.json")`
(`$AGENTSOFTWARE_STORAGE_CONFIG_DIR` or the platform config directory):

```json
{
  "llm": {
    "provider": "deepseek",
    "model": "deepseek-chat",
    "apiKeys": { "deepseek": "sk-..." },
    "retry": { "maxAttempts": 200, "delaySeconds": 10, "timeoutSeconds": 120 }
  },
  "schedule": {
    "secondsPerTick": 1.0,
    "simSecondsPerRealSecond": 1.0,
    "shiftStartHour": 8,
    "shiftEndHour": 18,
    "fastForwardIdleSeconds": 60,
    "wrapUpGraceSeconds": 600
  },
  "computer": { "defaultKind": "podman", "network": "maf-net",
                "image": "maf-base:latest", "containerfile": "Containerfile" },
  "mail": { "suffix": "company.com", "dataDir": "data/mail",
            "smtp": { "host": "", "port": 587, "user": "", "password": "",
                      "from": "", "useSsl": null } },
  "web": { "host": "0.0.0.0", "port": 8787, "replyTimeoutMs": 1200000 },
  "storage": { "dataDir": "data", "configDir": null, "cacheDir": null, "logDir": null },
  "toolkits": { "default": ["memory","note","time","todo","task_view",
                            "pc","mcp","skill","email","talk"] }
}
```

The LLM provider catalog (18 providers, 2 wire dialects) ships as the classpath
resource `providers.default.json`; a local `providers.json` in the config
directory can extend or override it.

---

## Personal computers

- `ComputerManager` allocates one `Computer` per role: `podman` (default
  container `maf-<role_id>` on `maf-net`), `local` (plain directory) or `ssh`.
- The container's home is the host folder `data/computers/<role_id>`; the shared
  cloud drive is `/mnt/drive` (`Public` + per-employee directories).
- MCP servers run inside the container over stdio (`podman exec -i`): the
  filesystem server plus an optional Playwright browser server.
- Computers are allocated lazily — the first `pc`/MCP tool call creates the
  container — and are powered off after the daily summary.

## Persistence and Web UI

- `StateRepository` (`adapters/persistence/JsonStateRepository`) stores a
  versioned snapshot: clock day/tick/base-date, role specs, pending and completed
  tasks. `Application.restoreState()` rebuilds the team.
- Notes, todos and the skill library are file-backed under the data root.
- `ChatWebAdapter` serves the UI and a versioned API (snake_case, method-checked):

| Method | Route | Purpose |
|---|---|---|
| GET | `/api/v1/state` | clock, pause state, group roster, client-conversation state |
| GET | `/api/v1/messages?since=N` | incremental chat/trace feed |
| POST | `/api/v1/reply` | submit the client reply (409 when nobody waits) |
| POST | `/api/v1/pause` / `/api/v1/resume` | pause or resume the whole simulation |
| POST | `/api/v1/attach` | Web attach heartbeat |

---

## Build and run

```bash
mvn test                      # 178 tests
mvn -q -DskipTests package    # builds target/agent-software.jar
```

Run the simulation (console input by default, `--web` for the browser UI):

```bash
java -cp target/classes com.agent.software.app.Main --days 1
java -cp target/classes com.agent.software.app.Main --web --days 3
```

`app.Main` accepts `--web`, `--days N`, `--roles CEO,COO,...` and `--verbose`.
On a host without podman, prefer `local` computers (set a role's
`computer_kind`/`computer_kwargs` or configure `computer.defaultKind`).

## Multiple applications in one process

```java
AppConfig config = ConfigLoader.load(AppPaths.resolve(AppConfig.defaults().storage())
        .configFile("config.json")).toAppConfig();
Application a = Application.create(config, new ConsoleInputAdapter());
Application b = Application.create(config, new ConsoleInputAdapter());
a.hireDefaultRoles();
b.hireRoles(List.of("CEO", "COO"));
a.start();
b.start();
```

Each `Application` owns its clock, team, tool service, mail, repositories,
computer registry and chat store; nothing is shared. This is covered by
`ApplicationEndToEndTest`.

---

## Repository layout

```
AgentSoftware/
├── pom.xml                     # Maven build (com.maf:agent-software)
├── Containerfile               # podman base image for role computers
├── docs/
│   ├── architecture.md
│   ├── agent-system-multi-instance.md
│   └── llm-provider-manager.md
├── src/main/java/com/agent/software/
│   └── kernel/ config/ domain/ ports/ runtime/ adapters/ tools/ app/ computers/
├── src/main/resources/
│   ├── role_templates.json     # 55 role templates, toolkits declared inline
│   ├── providers.default.json  # 18-provider LLM catalog
│   ├── mcp_group_rules.json    # MCP tool-name → logical group
│   └── web/                    # static Web UI assets
└── src/test/java/…             # tests for the new architecture
```

## Documentation

- [docs/architecture.md](docs/architecture.md) — layers, dependency direction, call order.
- [docs/agent-system-multi-instance.md](docs/agent-system-multi-instance.md) — running several applications in one JVM.
- [docs/llm-provider-manager.md](docs/llm-provider-manager.md) — provider catalog and LLM configuration.
