# Shift & Event-Driven Agent Scheduler

> **Current branch `java`: this branch is the Java rewrite** (Maven + JUnit 5).
> The original Python version lives on the `master` branch. The architecture, work-rest rules, and tool semantics are fully consistent with the Python version,
> with modules mapped one-to-one (see "Java Rewrite Notes" below).

> The project is still under development; according to DeepSeek it runs fine, but it has not been extensively tested.
> The project is built on Hermes + DeepSeek and will prioritize DeepSeek compatibility. It is still in the early stage of scaffolding and testing; the modules will be built out and improved incrementally.
> The section below was written by Hermes. The quoted parts were added by the author.

---

## Java Rewrite Notes (java branch)

### Build & Test

```bash
# Prerequisites: JDK 25+, Maven 3.8+ (OpenAI API Key: export OPENAI_API_KEY=sk-...)
mvn compile          # compile
mvn test             # run all JUnit tests (157 test cases)
mvn package          # package target/agent-software.jar
```

### Entry Points

```bash
mvn exec:java -Dexec.mainClass=com.agent.software.Main          # main entry: multi-day loop
mvn exec:java -Dexec.mainClass=demo.com.agent.software.RoleDemo # single-role demo
mvn exec:java -Dexec.mainClass=demo.com.agent.software.TalkDemo # talk collaboration-chain demo
mvn exec:java -Dexec.mainClass=demo.com.agent.software.McpDemo  # MCP tools demo
```

### LLM Provider Manager

`com.agent.software.llm.provider` provides an LLM **provider manager**: the
bundled catalog `src/main/resources/providers.default.json` lists mainstream
provider APIs (OpenAI, Anthropic, Gemini, DeepSeek, Groq, ...) with their base
URLs, request paths and API format (OpenAI or Anthropic); `ProviderManager`
merges it with an optional local providers file and can fetch each provider's
model list through its `GET /models` endpoint, normalizing both dialects into
`ModelInfo`. See `docs/llm-provider-manager.md`.

### Web UI (Group Chat + Client A Conversation)

The Java version ships with a zero-dependency web UI (`com.sun.net.httpserver`, no extra frameworks needed);
**it starts automatically when the main `Main` entry runs**, and a lightweight demo can also be run standalone:

```bash
# Option 1: main entry (full multi-role simulation; prints the web URL at startup)
mvn exec:java -Dexec.mainClass=com.agent.software.Main

# Option 2: lightweight demo (minimal team + a few preset messages; no computers created / no LLM calls)
mvn exec:java -Dexec.mainClass=com.agent.software.demo.WebDemo
```

Open the URL printed to the console (default `http://127.0.0.1:8787/`):

- **Left** — channel selector. The top **All Activity** channel is a live feed of *every* role across all
  groups (chain of thought, tool calls and final outputs, plus group chats and Client A conversations).
  Below it, each group (Leadership Group / Frontend Development Group / Backend Development Group / …)
  shows its member count; an unread badge appears when there are new messages.
- **Right** — chat window. Each message is rendered according to its kind:
  - 💬 `talk` / `client` — QQ-style bubbles (avatar + nickname + message; Client A messages are right-aligned blue);
  - 🧠 `reason` — a role's **chain of thought** (the LLM's `reasoning_content`, recorded per tool-loop round),
    shown as a collapsed-height monospace panel;
  - ✎ `note` — short assistant narration emitted while it is still calling tools;
  - 🛠 `tool` — a **tool call card**: the tool name, its arguments (pretty-printed JSON) and the tool's output;
  - ✔/✗ `answer` — the task's **final output** (result text, token usage; red styling when the task failed).
  The Leadership Group also shows conversations with **Client A (you)** (client messages appear as right-aligned blue bubbles).
- **Input box disabled by default** — enabled only when both conditions are met:
  1. The currently selected group is the **Leadership Group**;
  2. A Leadership Group member is calling `talk_to_client` to talk to you (waiting for your reply).
  When enabled, a prompt bar appears at the top; press Enter or click "Send" to reply to that member.
- Messages refresh in real time via polling (`/api/state` every 2s, `/api/messages` every 1.5s).

**Input channel for talking to Client A**: the reply is read through the `Input` instance held by the
`AgentSystem` (`AgentSystem.input`), decided when the system is created:
- `StdInput` (console): `talk_to_client` shows the question at the prompt and reads a line from `System.in`;
- `WebInput` (Web page): the question is recorded in the Leadership Group chat window, the input box is
  enabled automatically, and the reply typed on the page is returned (default timeout 20 minutes). When no
  browser is attached it returns an error instead of blocking forever.

The `target` passed to `Input.read(target)` is the conversation group — the marker distinguishing the
input box on the Web page; the console stream needs no such distinction.

| Config (env var / `-D` system property) | Default | Description |
|------|--------|------|
| `AGENTSOFTWARE_WEB_HOST` | `0.0.0.0` | Web UI listen address |
| `AGENTSOFTWARE_WEB_PORT` | `8787` | Web UI port |
| `AGENTSOFTWARE_CLIENT_REPLY_TIMEOUT` | `1200000` (20 min) | Reply timeout in ms for Client A replies in Web mode |

HTTP API (same-origin, no auth, polled by the web frontend):

| Endpoint | Description |
|------|------|
| `GET /api/state` | group roster + system time + Client A conversation state (`clientTalk.active` = whether someone is waiting for your reply) |
| `GET /api/messages?since=N` | new messages with seq greater than N (incremental fetch) |
| `POST /api/reply` `{"text":"…"}` | Client A submits a reply (succeeds only when someone in the Leadership Group is waiting, otherwise 409) |
| `POST /api/attach` | Web attach heartbeat |

Message payload kinds (`GET /api/messages`): `talk`, `client`, `reason`, `note`, `tool`, `answer`.
Trace messages (`reason` / `tool` / `answer`) carry structured metadata in the `extra` field —
for `tool`: `{tool, args, result, round, taskId}`; for `answer`: `{status: done|failed, tokens, taskId}`.

Implementation: `web/ChatStore.java` (message storage + Client A conversation coordination, one instance per AgentSystem) +
`web/ChatWebServer.java` (HTTP server + static assets in `src/main/resources/web/`);
the `talk_to_client` / `talk` tools record messages automatically; role workers record the trace feed in
`AgentRole.executeWithTools` / `RolePool.roleLoop` (LLM `reasoning_content` is surfaced through
`LLM.ToolsResponse.reasoning` / `LLM.ChatResponse.reasoning`). See tests `ChatStoreTest` /
`ChatWebServerTest` / `TalkToClientWebTest` / `AgentRoleTraceTest`.

### Python → Java Module Mapping

| Python module | Java class | Notes |
|---|---|---|
| `core/types.py` | `core/Types.java` | Event / AgentState / Priority |
| `core/event_bus.py` | `core/EventBus.java` | scheduled event dispatch table |
| `core/time_manager.py` | `core/TimeEventBus.java` | work-rest time engine + event bus (incl. ScheduledTask) |
| `core/dispatcher.py` | `core/EventDispatcher.java` | event broadcast + 3-layer filtering |
| `core/tools.py` | `core/ToolRegistry.java` | ToolDef / ToolKit / ToolRegistry |
| `core/roles.py` | `core/AgentRole.java` + `core/RolePool.java` | role system (incl. Task / Urgency / ToolLoopError) |
| `core/agent_system.py` | `core/AgentSystem.java` | unified management of TimeEventBus + RolePool |
| `core/llm.py` | `core/LLM.java` + `OpenAICompatLLM.java` | OpenAI-compatible client (Java HttpClient); reads only OPENAI_* env vars |
| `core/computer.py` | `core/Computer.java` + `PodmanComputer.java` + `SSHComputer.java` + `ComputerManager.java` | personal computer system |
| `core/mcp_client.py` | `core/MCPServer.java` | MCP stdio JSON-RPC client (newline-delimited) |
| `core/note_store.py` | `core/NoteStore.java` | notes + daily summary |
| `core/todo_store.py` | `core/TodoStore.java` | personal todos |
| `core/state_store.py` | `core/StateStore.java` | full state persistence (data/state.json) |
| `core/mail_service.py` | `core/MailService.java` | company email (virtual / SMTP via jakarta.mail) |
| `core/role_templates.py` | `core/RoleLoader.java` | 55 role templates (JSON description: `src/main/resources/role_templates.json`) |
| `core/role_factory.py` | `core/RoleFactory.java` | LLM-driven hiring |
| `core/pinyin_map.py` | — (merged into the `username` field of `role_templates.json`) | Chinese name → pinyin username |
| `core/path_manager.py` | `core/PathManager.java` | cross-platform paths |
| `core/config_store.py` | `core/ConfigStore.java` | JSON config (dot paths) |
| `python_tools/*.py` | `tools/toolkits/**` (template style: one Toolkit per domain + one Tool per function) | all tool classes (memory/note/time/todo/task_view/pc/mcp_manager/skill/email/talk/hr/client/hermes), see §8.1 |
| `main.py` | `Main.java` | main entry |
| `role_demo.py` / `talk_demo.py` / `mcp_demo.py` | `demo/RoleDemo.java` / `TalkDemo.java` / `McpDemo.java` | demos |
| `tests/*.py` | `src/test/java/**` (JUnit 5) | core test port (84 cases) |

### Differences from the Python Version

- Build/test with Maven (`pom.xml`); dependencies: Jackson (JSON), slf4j (logging), jakarta.mail (SMTP). Target JDK 25 (`maven.compiler.release=25`).
- All multithreading uses **Java 21+ virtual threads**: role workers (`RolePool`), parallel role assembly (`AgentSystem.addRoles`), and parallel computer restoration (`StateStore.restoreComputers`) all use virtual-thread executors; where rate limiting is needed (assembly/restoration), `Semaphore` preserves the original concurrency-limit semantics. Each role gets one resident virtual thread, no longer bounded by the fixed thread pool `max_workers`.
- LLM requests use the JDK `java.net.http.HttpClient`; retry semantics (retry on 429/5xx/timeouts, fail immediately on 4xx) match the Python version.
- The MCP client implements newline-delimited JSON-RPC 2.0 over stdio itself (no MCP SDK dependency), supporting `npx -y <package>` and custom commands (`podman exec -i` inside containers).
- Environment-variable override of path resolution also supports system properties (`-DAGENTSOFTWARE_DATA_DIR=...` etc.; the prefix is the app name
  uppercased, `AgentSoftware` → `AGENTSOFTWARE`), handy for test/container injection.
- **Unified layered LLM configuration resolution**: explicit constructor args &gt; Java args (`-D` system properties) &gt; env vars &gt;
  config file (ConfigStore, dot keys `llm.api_key` / `llm.base_url` / `llm.model`)
  &gt; defaults; key names are consistent across all sources (see the env-var table below). Providers are no longer distinguished; everything goes through
  the OpenAI-compatible interface (`OpenAICompatLLM`), reading only OpenAI-format env vars
  (`OPENAI_API_KEY` / `OPENAI_BASE_URL` / `OPENAI_MODEL`).
- Role templates JSON-ified: the 55 role templates are no longer hardcoded in Java; they are uniformly described by `src/main/resources/role_templates.json`
  (a top-level `role_id → role config` map); the `RoleLoader` class loads the registry automatically at class-load time.
  It also provides `RoleLoader.fromJsonMap / loadFromJson / templatesFromJson / registerFromJson / toJsonMap`
  methods to load or export `AgentRole` role objects from arbitrary JSON (string/file, supporting map/array/single-object forms)
  (see `RoleLoaderJsonTest`). The original `PinyinMap` is gone: each role's pinyin username is written directly into the JSON
  `username` field, falling back to `role_id` when not explicitly given.

### Multiple AgentSystem Instances (Multiple Systems Coexisting in One Process)

> The early design made the computer registry, mailbox, MCP/skill managers, the Client A conversation lock, the default clock, and more
> **process-wide global singletons**, with all data files also landing under a fixed `./data/*`, so only one `AgentSystem`
> could run safely in one JVM; otherwise roles/computers would overwrite each other, events would cross-talk, and saves would clobber each other —
> unpredictable failures (full assessment in `docs/agent-system-multi-instance.md`).

Since this version, each `AgentSystem` **directly owns its collaboration objects** (clock/computer registry/mailbox/
MCP & skill managers/Client A conversation lock/chat storage) and its data directory; one system can run standalone, and multiple systems
can safely coexist in the same process:

```java
import com.agent.software.AgentSystem;
import java.nio.file.Paths;

// each system owns its collaboration objects + its own data directory (logs/notes/todos/mail/saves/skills all live there)
AgentSystem companyA = new AgentSystem(Paths.get("data/company-a"), null, roleIds, 30.0, true);
AgentSystem companyB = new AgentSystem(Paths.get("data/company-b"), null, roleIds, 30.0, true);
```

- The no-arg/default constructor (`new AgentSystem(...)`) behaves the same: data still lands in `./data/*`
  (role notes' default directory is unified from the user home dir to `data/notes`, consistent with `.gitignore` and the overall project layout).
- Each system's persistent files (logs/notes/todos/mail/saves/skills) all land under its own `dataDir`.
- Roles bind to their system via `bindSystem`; standalone roles/role pools not bound to a system keep falling back
  to the process-level default singletons (old behavior, kept for demos and unit tests).
- Still process-wide shared (deliberately): the role-template registry (`RoleLoader.TEMPLATES`), default LLM config
  (ConfigStore), and host infrastructure such as podman networks/base images/container names.
  **Multi-instance deployment constraint**: if two systems on the same host use podman computers with the same `role_id`,
  container names collide; multi-instance setups should use `local` computers (each system has its own `base_dir`) or disjoint role sets.
- New `AgentSystemIsolationTest` covers isolation between two systems across clock/computers/mailbox/conversation lock/chat storage/data files.

---

A multi-role AI agent scheduling framework built on the idea of **corporate work-rest schedules and event-driven** operation.

It breaks the traditional agent `while(true)` loop, solving the problems of "long-task context explosion, unrecoverable state, runaway token costs, and no permission isolation".

Every role owns **a personal computer of its own** (a Podman container, all interconnected on the same custom bridge network);
files, tasks, notes, and MCP tools all live on its own computer, so permissions are naturally isolated.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│        TimeEventBus (time × event deeply bound)          │
│   (TimeManager merged into EventBus as of 2026-08)       │
│   - clock/Tick/day (1 Tick = 10 min, shift is Tick 0~60) │
│   - 3-layer filter pipeline (state mask → salience → wake)│
│   - event schedule: register_event(ev, tick) fires on time│
│   - Tick-driven: fast-forward only when all roles are idle│
│     (jump to task if any, else to shift end / next shift) │
└──────────────┬───────────────────────────────────────────┘
               │  SHIFT_START/SHIFT_END/TASK_DUE events
               ▼
┌──────────────────────────────────────────────────────────┐
│              EventDispatcher (event dispatcher)          │
│   trigger(event) → fan-out to ALL roles                  │
│   Each role runs Layer 1-2-3 filter independently        │
└──────────────┬───────────────────────────────────────────┘
               │  PASS events become Tasks
               ▼
┌──────────────────────────────────────────────────────────┐
│              RolePool (role thread pool)                 │
│   ThreadPoolExecutor — one dedicated thread per role     │
│   Priority Queue (heapq) — CRITICAL > HIGH > NORMAL      │
└──────────────┬───────────────────────────────────────────┘
               │  Task execution (native function calling)
               ▼
┌──────────────────────────────────────────────────────────┐
│      AgentRole + personal computer + MCP tools + talk    │
│   LLM(Task) → tool_calls → execute → role:tool feedback  │
│   - personal computer: podman container maf-<role>       │
│     (Ubuntu 24.04, powered on at shift start / off at end)│
│     container username = employee name in pinyin         │
│     (guoxiaodong), unique uid per employee               │
│     ships with sudo/git/node/python + Hermes Agent       │
│     (hermes_new_conversation)                            │
│   - corporate cloud drive: shared folder mounted         │
│     at /mnt/drive (Public 777 + employee dirs 755)       │
│   talk: inter-role communication                         │
└──────────────────────────────────────────────────────────┘
```

---

## Core Features

### 1. TimeEventBus — Time and Events Deeply Bound (`src/core/time_manager.py`)

`TimeManager` has been merged into `EventBus` (a `TimeEventBus(EventBus)` subclass) that is both the time source and the event bus:

- **Unified registration entry** `register_event(event, tick=None)`:
  - `tick=None` → delivered immediately (into the 3-layer filter pipeline)
  - `tick=N` → stored in the event schedule; the time thread delivers it automatically when due
- Work-rest events fire automatically: every day Tick 0 → `SHIFT_START` (shift start), Tick 60 → `SHIFT_END` (shift end)
- **Tick-driven (does not flow with real time)**: Ticks freeze while roles are busy (an LLM finishes its work within one Tick, so slow processing never makes it miss future-Tick tasks); fast-forward happens only after all roles have been idle for 60s — jump to the next task Tick if tasks exist, otherwise to today's shift end, or to tomorrow's shift start if already off duty
- Notes and scheduled tasks are unified (collectively called notes): `write_note` with `remind_tick` set is a note with a reminder that fires a reminder event like a task when due; the underlying `schedule_task` only saves the task list; same-day tasks are registered as events directly, and next-day tasks are loaded automatically at shift start on the target day
- The compatibility alias `TimeManager` was removed in 2026-08 (commit `2953835`) — use `TimeEventBus` uniformly

### 2. The 3-Layer Event Filter (`src/core/roles.py` — `AgentRole.evaluate_event`)

0-Token interception of low-value events. **Filtering is per-role independent** (role differentiation: each role's
`interest_keywords`/`skills`/state), invoked during dispatch by `EventDispatcher.trigger`;
`EventBus` only maintains the timed-event schedule, with no filter pipeline (consolidated in 2026-08).

| Layer | Name | Mechanism | Token |
|----|------|------|-------|
| Layer 1 | State Mask | OFF_DUTY state intercepts non-EMERGENCY events | 0 |
| Layer 2 | Salience Evaluator | role keyword hits + priority weighting (`priority*0.4 + relevance*0.6`) | 0 |
| Layer 3 | Wake | events passing the first two layers become Tasks in that role's queue | on demand |

System-time events (`source="time"`, e.g. SHIFT_START/END) bypass Layer 2 and pass straight through.

> This part may later train a small model to do the filtering; for now training seems to have little value.

### 3. Fast-Forward (`src/core/time_manager.py`)

In real-time mode you don't have to wait idly: after all roles have been idle (no tasks processing/queued) for **1 minute**,
the clock automatically jumps to the next event Tick (scheduled event / scheduled task / shift end; if already off duty, it jumps to the next day's shift start).
`set_idle_checker` / `set_fast_forward(enabled, idle_seconds)` are configurable.

### 4. Multi-Role Concurrent Task Scheduling (`src/core/roles.py`)

- Each role has its own thread + its own lock + its own LLM instance
- Priority task queue (heapq): CRITICAL(10) > HIGH(6) > NORMAL(3) > LOW(1)
- `RolePool.add_role_and_start()` dynamic onboarding (hire and start immediately via HR)
- `RolePool.remove_role()` offboarding (auto power-off + removal from the team)

### 5. Native Function Calling (`src/core/llm.py` + `src/core/roles.py`)

- Requests carry a `tools` declaration + `tool_choice:"auto"`; tool calls are determined from the structured `message.tool_calls` field of the response
- Tool results are fed back with `role:"tool"` + `tool_call_id`
- The loop has protective caps: at most 20 tool-call rounds / 200K tokens accumulated per task; tasks exceeding the caps are marked failed
  (prevents the LLM from burning tokens endlessly in a degenerate tool-calling loop); API timeout/error texts
  (`[API timeout]` / `[API error: ...]`) are likewise treated as failures, not successful results
- `max_tokens` has no cap by default (long-content JSON isn't truncated into invalid JSON; the field is omitted when `None`)
- The text protocol (```tool_call blocks + the `_parse_tool_calls` regex) was removed with commit `2953835`; only native function calling remains

### 6. Personal Computer System (`src/core/computer.py`)

Each role gets its own computer, by default a **Podman container** (image `maf-base:latest`, named `maf-<role_id>`):

- The container mounts host dir `data/computers/<role>` ↔ `/home/agent` inside the container (the same files, visible both ways)
- The default image `maf-base:latest` is defined by the `Containerfile` at the project root (Aliyun mirrors / standard apt packages / Hermes / MCP server built in one pass);
  if the image doesn't exist when a computer is initialized, `podman build` creates it automatically; role containers are copied from that image, built in seconds with only the employee user added
- **Auto power-on at shift start** (SHIFT_START), **auto power-off at shift end** (after the summary)
- The same custom bridge network `maf-net`: computers can talk to each other (the `lan_devices` tool looks up names/computer names/IPs)
- `ComputerManager` (global singleton) manages allocation/destruction
- **Podman is a hard requirement**: without podman on the machine, the `PodmanComputer` constructor throws `RuntimeError` directly
  (auto-degradation removed in commit `2953835`) — for local simulation set `computer_kind="local"` explicitly
- There is also `SSHComputer` (remote host; requires an explicit host)
- **Auto-reconnect MCP servers across days**: each day's shift-end `podman stop` kills the MCP server's
  stdio pipe inside the container; at the next shift start, session liveness is probed and the server is rebuilt automatically (otherwise all file tools would fail from day 2 on)

### 7. MCP Tools — Servers Run Inside Role Computers' Containers

- **Each computer has one dedicated MCP filesystem server**, started inside the container via `podman exec -i`,
  authorized directory = `/home/agent` (literally identical to the working directory the LLM sees; no path-space mismatch)
- The base image (`Containerfile`) globally preinstalls `@modelcontextprotocol/server-filesystem` at build time, so containers work instantly on start (old containers get it installed as a fallback by `_ensure_container`)
- `DEFAULT_MCP_GROUPS = ("file_ops",)`: when a role joins or is newly onboarded, the file-operation tools are automatically installed on its personal computer
- `MCPManager` (globally shared): `mcp_search/mcp_list/mcp_add/mcp_remove/mcp_my_tools`
  lets the LLM self-service install tools from other tool groups

### 8. Default Tools (`src/python_tools/`)

| Tool class | Tools | Description |
|--------|------|------|
| memory | summary / write_note / edit_note / list_notes / read_note | memory + shift-end summary (auto power-off) |
| time | get_time / take_rest | work-rest schedule |
| task | create_task / list_tasks / edit_task / delete_task | scheduled tasks (Tick reminders, persisted to the computer's tasks/) |
| computer | run_command / computer_status / lan_devices / reboot | personal computer operations |
| mcp_manager | mcp_search / mcp_list / mcp_add / mcp_remove / mcp_my_tools | MCP self-service management |
| talk / list_roles | inter-role communication (only same-group members can message each other; cross-group goes through email) | auto-injected at pool.start() |
| email | send_email / read_mail / open_mail / mail_address_book | employee email (virtual mailbox; real sending once SMTP is configured) |
| MCP file_ops | read_file / write_file / edit_file / ... | the default MCP group, auto-installed on computers |

Exclusive tools: the CEO has `talk_to_client` (Client A communication); HR has `post_job_posting` / `list_candidates`.

### 8.1 Template-Style Tool Classes (Java Version `src/main/java/com/agent/software/tools/toolkits/`)

The Java version's default assembly has switched to the template-style implementation: one `Toolkit` subclass per business domain + one
`Tool` subclass per function (using `toolkits/note/WriteNote` as the template):

| Toolkit (class) | Tools | Description |
|--------|------|------|
| `toolkits.memory.Memory` | `summary` | **memory-only content** (daily summary; auto-injected into the prompt the next day + power-off at shift end) |
| `toolkits.note.Note` | `write_note` / `edit_note` / `list_notes` / `read_note` / `delete_note` | notes (**split out of memory**; notes and timed reminders unified) |
| `toolkits.time.Time` | `get_time` / `take_rest` | work-rest schedule |
| `toolkits.todo.Todo` | `todo_add` / `todo_list` / `todo_update` / `todo_delete` | todo list |
| `toolkits.taskview.TaskView` | `my_tasks` | task queue + history |
| `toolkits.pc.Pc` | `run_command` / `computer_status` / `lan_devices` / `reboot` | **pc = the computer tools** (personal computer operations) |
| `toolkits.mcp.McpManager` | `mcp_search` / `mcp_list` / `mcp_add` / `mcp_remove` / `mcp_my_tools` | MCP self-service management |
| `toolkits.skill.Skill` | `skill_search` / `skill_list` / `skill_add` / `skill_remove` / `skill_my_skills` | SKILL.md skill management |
| `toolkits.email.Email` | `send_email` / `read_mail` / `open_mail` / `mail_address_book` | employee email |
| `toolkits.hermes.Hermes` | `hermes_new_conversation` / `hermes_send` | calls the Hermes Agent on the computer |
| `toolkits.talk.Talk` | `talk` / `list_roles` | inter-role communication (same group messages each other; cross-group goes through email) |
| `toolkits.hr.Hr` | `post_job_posting` / `list_candidates` | hire-to-onboard (HR exclusive) |
| `toolkits.client.Client` | `talk_to_client` | Client A communication (CEO exclusive) |

Base classes: `tools.Tool` (getToolName / getSchema / getInputSchema / handler) and
`tools.Toolkit` (addTool / getTools / trigger). `AgentRole.addToolkit(Toolkit)`
registers template-style tool classes directly: each `Tool` becomes a Python-native tool, and the flat parameter description is
automatically converted by `Tool.getInputSchema()` into an OpenAI-style `input_schema` for the LLM to call.

### 9. Hire-to-Onboard (`src/core/hr_toolkit.py` + `src/core/roles.py`)

HR posts a job → the background `RoleFactory` generates the new hire → **immediately joins the running team and starts a worker**
(`add_role_and_start`) → the new hire automatically gets all default tools + MCP file_ops + a dedicated computer.
No interview step; after onboarding, HR notifies the COO.

### 10. Yesterday's Memory Injection (`src/core/note_store.py` + `src/core/roles.py`)

- Every shift end, `summary` is called to save the day's summary (`_summary_day_<N>.md`, stored in the role computer's notes/)
- The next day, `build_system_prompt()` auto-injects `[Yesterday's Summary]` (the most recent one strictly before today)
- This requires the computer to be powered on — guaranteed by the SHIFT_START auto power-on

> The memory system relies on the day loop: like a person, yesterday's fragmented work memories are today's memories. Hence a summary every day.
> Memory also includes notes, tasks, and files in the workspace. These are external memories — you won't know them unless you actively look them up.

### 11. The 12 Predefined Role Templates (`src/core/role_templates.py`)

**Leadership (default roles)**:

| Role | Name | Duties |
|------|------|------|
| CEO | Lin Zong | receives user requirements → strategic goals → summary reports |
| COO | Chen Zong | breaks down goals → takes stock of employees → initiates hiring |
| HR | Wang Renshi | hiring requests → posts jobs → new hires onboard immediately (no interview) |
| CFO | Qian Cai | budget approval → token limits → high-risk approvals (template kept, not yet enabled) |
| Requirements Analyst | Xu Ruonan | communicates requirements with Client A (the user)/leadership → produces the "Requirements Specification" |

**Technical team**: architect (Wang Jianguo) / fullstack_dev (Li Ming) / reviewer (Zhang Wei) / qa_engineer (Liu Yang) / ops_engineer (Zhao Qiang)

**Business team**: content_marketer (Chen Jing) / data_analyst (Sun Xiao) / support_agent (Zhou Mei)

### 12. Dynamic Role Factory (`src/core/role_factory.py`)

Hiring needs → LLM generates the role config → a new AgentRole → onboarded and working. Non-duplicate names are assigned automatically (24-name name pool).

### 13. Employee Groups + Company Email (`src/core/role_templates.py` + `src/core/mail_service.py` + `src/python_tools/email_toolkit.py`)

**Groups**: every member belongs to a group (`AgentRole.group`); the default team split is as follows —

| Group | Members |
|------|------|
| Leadership Group | CEO Lin Zong / COO Chen Zong / HR Wang Renshi / CFO Qian Cai / CTO Gao Yuan / Requirements Analyst Xu Ruonan |
| Frontend Development Group | frontend_lead Chen Siyuan + frontend_dev_1~3 |
| Backend Development Group | backend_lead Wang Yuxuan + backend_dev_1~3 |
| Mobile Development Group | mobile_lead Zhang Yating + mobile_dev_1~3 |
| Full-Stack Development Group | fullstack_lead Li Junjie + fullstack_dev_1~3 |
| Testing Group | test_lead Liu Zihan + tester_1~20 |
| Security Group | attacker_1~3 (red-blue team exercise/audit) |
| Architecture & Release Group | architect Wang Jianguo + release_manager Fang Jinyan |
| Operations Group / Marketing Group / Data Group / Support Group | ops_engineer Zhao Qiang / content_marketer Chen Jing / data_analyst Sun Xiao / support_agent Zhou Mei |

- **The talk tool is limited to same-group communication**: cross-group sends are rejected with a prompt to use email instead
  (new hires onboarded via hiring are ungrouped and unrestricted — they can talk to anyone).
- Groups appear in the `list_roles` roster, the `mail_address_book` contacts, and system prompts.

**Company email**: each member gets a mailbox `username@<suffix>` (the suffix is user-defined, see the env vars below),
e.g. Guo Xiaodong → `guoxiaodong@company.com`; an explicit `email` field on a role overrides it.

**Employee email tools** (auto-assembled for every role):

| Tool | Description |
|------|------|
| send_email | send email to colleagues (by name or address; multiple recipients and CC supported) — the first choice for cross-group communication |
| read_mail | view the inbox (newest first, unread markers, filter to unread only) |
| open_mail | open a full email (auto-marked as read) |
| mail_address_book | company address book (members listed by group with name/email/position) |

**Virtual implementation (default)**: mail is delivered to internal mailboxes, persisted in `data/mail/mailboxes.json`
(recoverable after restart; gitignored). **Real sending auto-activates once SMTP is configured**: real mail is sent via smtplib
while a copy is still delivered to the internal recipient mailbox (simulating intranet reading).

---

## Project Structure

```
AgentSoftware/
├── src/
│   ├── core/
│   │   ├── types.py           # data types such as Event, AgentState, Priority
│   │   ├── event_bus.py       # EventBus: scheduled event dispatch table (_tick_schedule)
│   │   ├── time_manager.py    # TimeEventBus: time (clock/Tick/day) + event bus + fast-forward
│   │   ├── roles.py           # AgentRole + RolePool (multi-role thread pool + dynamic onboarding/offboarding)
│   │   ├── agent_system.py    # unified management: TimeEventBus + RolePool + event dispatch
│   │   ├── computer.py        # Computer base class + Podman/Local/SSH + ComputerManager
│   │   ├── note_store.py      # notes + daily summary (stored per day, via the role computer)
│   │   ├── role_templates.py  # 12 predefined role templates
│   │   ├── role_factory.py    # LLM-driven dynamic role creation
│   │   ├── dispatcher.py      # broadcasts events to all roles
│   │   ├── tools.py           # ToolRegistry: tool registration + to_openai_tools
│   │   └── llm.py             # OpenAI-compatible client (native function calling)
│   ├── python_tools/          # Python tool classes (DEFAULT_TOOLKITS)
│   │   ├── memory_toolkit.py  # summary (summarize + shift end + power off) / notes
│   │   ├── time_toolkit.py    # get_time / take_rest
│   │   ├── task_toolkit.py    # scheduled task CRUD (persisted to the computer's tasks/)
│   │   ├── computer_toolkit.py# run_command / computer_status / lan_devices / reboot
│   │   ├── mcp_manager.py     # MCPManager: mcp_search/add/remove (installs onto personal computers)
│   │   ├── mcp_toolkit.py     # MCPServer: server connection (custom start commands supported)
│   │   ├── hr_toolkit.py      # post_job_posting (hire-to-onboard) / list_candidates
│   │   ├── client_toolkit.py  # talk_to_client (Client A communication, CEO exclusive)
│   │   └── talk_toolkit.py    # talk / list_roles (inter-role communication)
│   ├── config/
│   │   └── mcp_group_rules.json  # MCP server and tool-group configuration
│   ├── main.py                # main entry: multi-day loop (auto-enters the next day)
│   └── mcp_demo.py            # MCP tool-call demo
└── data/
    ├── main_run.log           # run log
    └── computers/<role>/      # role computers (host-side mount directory)
```

---

## Quick Start

### Prerequisites

- Python 3.10+, `pip install -r requirements.txt` (or use the project's bundled `.venv/`)
- [podman](https://podman.io/) (the container runtime for each role's computer, **required**; for local simulation explicitly use `computer_kind="local"`)
- OpenAI API Key (env var `OPENAI_API_KEY`; `OPENAI_BASE_URL` / `OPENAI_MODEL` can also point to
  any OpenAI-compatible endpoint, e.g. DeepSeek / local vLLM)

```bash
cd AgentSoftware
source .venv/bin/activate

# set the API Key (required; no longer hardcoded in source)
export OPENAI_API_KEY="sk-..."

# run the full work-rest demo (multi-day loop, auto-enters the next day)
python src/main.py
```

### Programmatic Use

```python
from src.core.agent_system import AgentSystem
from src.core.types import Event, Priority

# unified management of TimeEventBus + RolePool + event dispatch
system = AgentSystem(role_ids=["CEO", "COO", "HR"])
system.start()   # starts role threads + the time thread (Tick 0 / day 1)

# dispatch an event (SHIFT_START/SHIFT_END are triggered automatically by the time thread)
system.trigger(Event(source="github", event_type="new_pr",
                     priority=Priority.HIGH, payload={"pr_number": 188}))

# register a scheduled event (fires at the given Tick; without tick it's delivered immediately)
system.time_manager.register_event(
    Event(source="meeting", event_type="standup", priority=Priority.HIGH),
    tick=30,
)

print(system.describe())   # day X, Tick Y (on shift / off duty)
system.stop()
```

### Manual Assembly

```python
from src.core.dispatcher import EventDispatcher
from src.core.roles import RolePool
from src.core.role_templates import get_template
from src.core.time_manager import TimeEventBus

pool = RolePool()
pool.add_role(get_template("CEO"))

tm = TimeEventBus()
tm.set_event_sender(lambda ev: EventDispatcher(pool).trigger(ev))
tm.start()
pool.start()
```

---

## Usage Examples

### Inter-Role Communication

```python
coo = pool.get_role("COO")
coo.talk_to("HR", "Please post a job opening: we need a backend engineer proficient in Rust", "HIGH")
# → HR queue receives: [FROM COO(Chen Zong)] Please post a job opening...
```

> talk is limited to same-group members; CEO/COO/HR all belong to the "Leadership Group" so they can message each other. Use email for cross-group communication.

### Employee Email (Virtual Mailbox / Real SMTP Sending)

```python
# sender: Guo Xiaodong (Testing Group) → recipient: Wang Jianguo (Architecture & Release Group, cross-group)
tester = pool.get_role("tester_1")
tester._tools.call_tool("send_email", {
    "to": "Wang Jianguo",
    "subject": "Architecture question",
    "body": "I'd like to ask about best practices for the permission checks in the login module.",
})
# → email sent to wangjianguo@company.com, subject "Architecture question", delivered to the virtual mailbox.

# recipient checks the inbox
architect = pool.get_role("architect")
inbox = architect._tools.call_tool("read_mail", {"limit": 5})
mid = inbox.split("id=")[-1].split(")")[0].strip()
print(architect._tools.call_tool("open_mail", {"message_id": mid}).content[0].text)
```

### HR Hire-to-Onboard

```python
hr = pool.get_role("HR")
result = hr._tools.call_tool("post_job_posting", {
    "requirement": "Need a backend engineer proficient in Rust, familiar with gRPC and PostgreSQL",
})
# → a new hire is generated in the background → immediately joins the team and starts working (add_role_and_start)
# → returns the new hire's full profile: role_id/name/skills, status "joined the team and started working"
```

### Viewing Intranet Computer Devices

```python
dev = pool.get_role("CEO")
print(dev._tools.call_tool("lan_devices", {}))
# → intranet computer devices:
#   - Lin Zong (CEO) | computer maf-CEO | 10.89.0.2
#   - Chen Zong (COO) | computer maf-COO | 10.89.0.3
```

---

## Environment Variables

All LLM variables in the table below can instead be provided as Java args (`-D<same name>`, highest priority) or in the config file
(ConfigStore, keys `llm.api_key` / `llm.base_url` / `llm.model`,
lowest priority); defaults are used only when none of the three is set. Everything goes through the OpenAI-compatible interface,
with no backend distinction: just point `OPENAI_BASE_URL` / `OPENAI_MODEL` at any OpenAI-compatible service.

| Variable | Default | Description |
|------|--------|------|
| OPENAI_API_KEY | (empty) | OpenAI-compatible API key (when unset, no Authorization header is sent — suitable for keyless local endpoints) |
| OPENAI_BASE_URL | https://api.openai.com | OpenAI-compatible API base URL (e.g. DeepSeek / vLLM / Ollama OpenAI endpoints) |
| OPENAI_MODEL | gpt-4o-mini | model name |
| MAIL_SUFFIX | company.com | company email domain suffix (user-defined, e.g. `mycorp.com` → `guoxiaodong@mycorp.com`) |
| SMTP_HOST | (empty) | SMTP server address; **setting it switches mail from the virtual implementation to real sending** |
| SMTP_PORT | 587 | SMTP port (465 auto-uses SSL) |
| SMTP_USER / SMTP_PASSWORD | (empty) | SMTP login credentials (optional) |
| SMTP_FROM | SMTP_USER or the sender themselves | real-mail sender address (optional) |
| SMTP_USE_SSL | by port | `true`/`false` to force an SSL or non-SSL connection |
| MAIL_DATA_DIR | data/mail | internal mailbox data directory (where mail persists in virtual mode) |

### Company Email: Virtual Implementation → Real SMTP Sending

By default the **virtual implementation**: mail is only delivered within the simulation (recipients = team-member mailboxes), persisted to
`data/mail/mailboxes.json` and recoverable after restart. Once SMTP is configured it switches to **real sending**
(smtplib), while keeping an internal mailbox copy for roles to keep reading:

```bash
# virtual implementation (default): nothing to configure; mail between employees is delivered straight to internal mailboxes
export MAIL_SUFFIX="mycompany.com"

# real sending: add SMTP config and it switches automatically
export SMTP_HOST="smtp.mycompany.com"
export SMTP_PORT=587
export SMTP_USER="agent@mycompany.com"
export SMTP_PASSWORD="********"
```

> SMTP send failures return an error (no delivery), making config problems easy to spot; successfully sent mail is
> marked "sent for real via SMTP" and delivered into the internal recipient mailbox.

### Using Other OpenAI-Compatible Backends

The project uses the official OpenAI API by default. To switch to any OpenAI-compatible endpoint (DeepSeek / local vLLM,
LM Studio, Ollama, etc.; keyless services can omit `OPENAI_API_KEY`), set the
env vars (or `-DOPENAI_BASE_URL=...` / config file `llm.base_url`) and run as usual;
role threads and the hiring flow (`RolePool`/`RoleFactory`) automatically use that endpoint
(`OpenAICompatLLM` goes through the OpenAI interface uniformly):

```bash
export OPENAI_BASE_URL="https://api.deepseek.com"   # or http://localhost:11434/v1 (Ollama)
export OPENAI_MODEL="deepseek-v4-flash"             # or a local model tag
export OPENAI_API_KEY="sk-..."                      # can be omitted for keyless local endpoints
ollama serve                                        # make sure the local service is running (if using Ollama)
python src/role_demo.py                             # example: the whole multi-role system uses that endpoint
```

Code-level specification also works: `RolePool(llm_api_key=..., llm_model=...)` /
`RoleFactory(api_key=..., model=...)` with explicit args, or
create a client directly with `new OpenAICompatLLM(apiKey, baseUrl, model, label, configStore)`;
constructor args have the highest priority (above env vars and the config file).

### State Persistence (StateStore)

All serializable state during a `main.py` run is saved uniformly to `data/state.json` (atomic JSON writes, no database):

- **Role profiles** — name/position/duties/personality/skills/state
- **Task history** — each role's completed/failed tasks (incl. talk messages and results = conversation/work records)
- **Incomplete tasks** — queued todos, resumed after restart
- **Computer/container info** — podman container types/name mappings; existing containers are re-bound after restart, not rebuilt
- **Time progress** — day/Tick; the work-rest schedule resumes after restore

**Auto-save on exit** (Ctrl+C or normal termination), **auto-load of the last progress on startup** (resuming from the previous day).

```python
from src.core.state_store import StateStore
store = StateStore()
if store.exists():
    store.restore(system)   # load on startup
store.save(system)          # save on exit
```

### Role Activity Journals

Each role has an activity journal (`data/journals/<role_id>.md`, gitignored, not committed), recording that role's
context updates: task received / execution started / tool calls / note writes / messages sent & received / WAIT state changes /
events accepted and skipped. **Global notifications (SHIFT_START/SHIFT_END work-rest events, broadcast events) are written to every role's
journal**, making it easy to review team activity in one place.

Line format: `[D<day> T<Tick> HH:MM:SS] content`

```python
role.journal("any activity record")   # writes to that role's own file
pool.journal_all("global notification")   # writes one entry to every role's journal
```

---

## Module Structure (Per-Py-File Explanation)

> Every `.py` file has complete interface docs in its header (module description + class + method list).

### Core Layer `src/core/` — Roles / Computers / Time / LLM / Persistence

| File | Module description | Main classes / functions |
|---|---|---|
| `roles.py` | role-system core: AgentRole (a single role: task queue/state machine/tool assembly/group/mail_address/talk/WAIT synchronous wait/journal) + RolePool (thread-pool scheduling; journal created on registration) | `AgentRole`, `RolePool`, `Task`, `Urgency`, `ToolLoopError` |
| `computer.py` | personal computer abstraction: LocalComputer (local fallback) / PodmanComputer (maf-base container: pinyin users + /mnt/drive cloud-drive mount + Hermes; image built from the project-root Containerfile) / SSHComputer; ComputerManager management + custom `maf-base` image build/reuse | `Computer`, `LocalComputer`, `PodmanComputer`, `SSHComputer`, `ComputerManager`, `create_computer` |
| `time_manager.py` | work-rest time engine + event bus: Tick/day/shift-start-end events, scheduled tasks, Tick fast-forward only when all roles are idle | `TimeEventBus`, `ScheduledTask` |
| `event_bus.py` | event-bus base class: register/cancel/schedule events | `EventBus` |
| `dispatcher.py` | event dispatcher: broadcasts events to all roles | `EventDispatcher` |
| `llm.py` | LLM backend: the OpenAICompatLLM concrete class (chat/summarize/tool calls/retries), unified OpenAI interface, reads only OPENAI_* env vars | `OpenAICompatLLM` |
| `role_templates.py` | 55 role templates (47 default): CEO/COO/HR/CTO/requirements analyst/tech leads/frontend-backend-mobile-fullstack/testers/attackers, etc. | `ceo`, `coo`, `frontend_dev`, `create_all_roles`, `get_template`, `TEMPLATES` |
| `role_factory.py` | role factory: creates roles from templates (with specified api_key/model) | `RoleFactory` |
| `agent_system.py` | team system: assembles multiple roles + the time engine + event dispatch, parallel power-on | `AgentSystem` |
| `tools.py` | tool registry: ToolDef (tool definition) / ToolKit (tool class) / ToolRegistry (unified registration/calling/OpenAI schema export) | `ToolDef`, `ToolKit`, `ToolRegistry` |
| `types.py` | common types: AgentState (role state machine) / Priority / Event | `AgentState`, `Priority`, `Event` |
| `note_store.py` | notes + daily summary storage (notes carry remind_tick = scheduled tasks) | `NoteStore` |
| `todo_store.py` | personal todo list (data/todos/<role_id>.json) | `TodoStore` |
| `state_store.py` | full state persistence (atomic writes to data/state.json, restore on restart) | `StateStore` |
| `mail_service.py` | company email core: mailbox address assignment (username@user suffix) + virtual/SMTP delivery + mailbox persistence (data/mail) | `MailService`, `MailConfig`, `MailMessage` |
| `pinyin_map.py` | Chinese role name → pinyin mapping (container usernames, cloud-drive permissions) | `NAME_PINYIN`, `to_pinyin` |

### Tool Layer `src/python_tools/` — LLM-Callable Tools (ToolKit)

| File | Module description | Tools (LLM call names) |
|---|---|---|
| `__init__.py` | tool-class registry: DEFAULT_TOOLKITS default assembly list + factory imports | `DEFAULT_TOOLKITS` |
| `memory_toolkit.py` | notes/daily summary | `write_note` `edit_note` `list_notes` `read_note` `summary` |
| `time_toolkit.py` | work-rest schedule | `get_time` `take_rest` |
| `todo_toolkit.py` | personal todos | `todo_add` `todo_list` `todo_update` `todo_delete` |
| `task_view_toolkit.py` | task list view | `my_tasks` |
| `hermes_toolkit.py` | calls the Hermes Agent on the computer (new conversation/send message, synchronously waits for the result) | `hermes_new_conversation` `hermes_send` |
| `computer_toolkit.py` | computer operations | `run_command` `computer_status` `reboot` |
| `mcp_manager.py` | MCP tool self-service management | `mcp_search` `mcp_list` `mcp_add` `mcp_remove` `mcp_my_tools` |
| `mcp_toolkit.py` | MCP client: server connection/tool loading (mcp-server-filesystem) | `MCPServer`, `MCPToolLoader` |
| `skill_toolkit.py` | skill library (shared data/skills) | `skill_search` `skill_add` `skill_list`, etc. |
| `talk_toolkit.py` | role communication (addressed by name, **same-group members only**, wait synchronous wait, deadlock detection) | `talk` `list_roles` |
| `email_toolkit.py` | company email (mail exchange between employees; the first choice for cross-group communication) | `send_email` `read_mail` `open_mail` `mail_address_book` |
| `client_toolkit.py` | communication with Client A | `talk_to_client` |
| `hr_toolkit.py` | HR (hiring) | `hire`, etc. |

### Entry Points and Demos `src/`

| File | Module description |
|---|---|
| `main.py` | system main entry: full 46-role team simulation (restore progress → loop through days → save state) |
| `role_demo.py` | demo: single-role creation + tool assembly + task execution |
| `talk_demo.py` | demo: 4-role talk collaboration chain |
| `mcp_demo.py` | demo: MCP filesystem installation + tool calls |

### Tests `tests/`

| File | Coverage |
|---|---|
| `test_event_bus.py` | event-bus registration/triggering/cancellation |
| `test_time_manager.py` | Tick advancement/work-rest events/scheduled tasks/fast-forward |
| `test_journal.py` | role activity journals |
| `test_talk_wait.py` | talk communication / WAIT synchronous wait / deadlock-ring detection / attachments |
| `test_talk_group.py` | talk same-group restriction (same group allowed / cross-group rejected / ungrouped unrestricted) |
| `test_mail.py` | mailbox assignment / virtual delivery / real SMTP sending / persistence / address book |
| `test_state_store.py` | state persistence save/restore |
| `test_llm_retry.py` | LLM retry semantics (mock requests) |
| `test_note_store.py` / `test_note_reminder.py` | note storage / note reminders = scheduled tasks |
| `test_todo_taskview.py` | todo list + task list |
| `test_pinyin.py` | pinyin mapping / uid assignment / prompt includes cloud-drive and Git conventions |
| `test_hermes_toolkit.py` | Hermes conversation tools (create session/send/error prompts) |
