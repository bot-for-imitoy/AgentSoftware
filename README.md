# AgentSoftware — Shift & Event-Driven Agent Scheduler

A **multi-role AI agent "software company" simulator** written in Java (Maven, JUnit 5).
A team of LLM-powered employees — CEO, COO, HR, team leads, developers, testers, security
engineers, … — runs like a real company: it works on a **corporate shift clock**, reacts to
**events** instead of spinning in a `while(true)` loop, and every employee owns a **personal
computer** of its own where its files, notes, tools and MCP servers live.

This design solves the classic problems of long-running agent loops:
context explosion on long tasks, unrecoverable state, runaway token costs, and missing
permission isolation. Work-rest discipline keeps each agent's context bounded (one task at a
time, summaries persisted every day), events decide *whether* an agent should wake up
(0-token filtering), and per-role computers give every agent an isolated filesystem.

> Status: actively developed. The engine, role system, toolkits, Web UI and persistence are in
> place and covered by **196 JUnit tests**; the simulation flow itself keeps being refined.

---

## Contents

- [Key Ideas](#key-ideas)
- [Repository Layout](#repository-layout)
- [Requirements](#requirements)
- [Build & Test](#build--test)
- [Running the Simulation](#running-the-simulation)
- [How a "Day" Works](#how-a-day-works)
- [Architecture Overview](#architecture-overview)
- [Core Features](#core-features)
- [Configuration Reference](#configuration-reference)
- [Multiple AgentSystems in One Process](#multiple-agentsystems-in-one-process)
- [Documentation](#documentation)

---

## Key Ideas

- **Shift-driven on a simulated calendar clock, not loop-driven.** A shared `TimeEventBus`
  provides the clock and the event bus at the same time. The simulation runs on a **calendar
  clock** with **second-level ticks**: by default **1 Tick = 1 simulated second** (configurable),
  each work day starts at **08:00:00 of a real calendar date** (day 1 = the day the run starts,
  `SHIFT_START`) and the shift ends at **18:00:00** (`SHIFT_END`, tick 36000 by default). The
  clock **advances while roles are busy** (by default in real time — 1 simulated second per real
  second of team work), **fast-forwards to the next event Tick** once the whole team has been idle
  for a while, and after the end-of-day summaries **rolls over to the next day at 08:00:00** and
  loops — nobody waits in real time.
- **0-token event filtering.** Every event passes a per-role 3-layer filter (state mask →
  keyword salience → wake) before it ever costs a token. Irrelevant events are dropped for free,
  which is what keeps a large team affordable.
- **One employee = one computer.** Each role owns a Podman container (`maf-<role_id>` on the
  `maf-net` bridge network), built from the project's `Containerfile`. The container's home
  directory is the host folder `data/computers/<role_id>` — the same files, visible both ways.
  Tools, notes, tasks and MCP servers live *inside* that computer, so permissions are naturally
  isolated.
- **A believable little company.** Roles belong to groups (Leadership, Frontend/Backend/Mobile/
  Full-Stack Development, Testing, Security, …), chat within their group (`talk`), use company
  email (virtual mailbox, optionally real SMTP), hire new colleagues on the spot (`post_job_posting`),
  and the CEO talks to **you** — the client — through the console or the Web UI.
- **Java 21+ virtual threads.** Each role runs on its own resident virtual thread; LLM requests
  use the JDK `HttpClient` with retry semantics (429 / 5xx / timeouts).
- **Everything is configurable, JSON-first.** Role templates live in
  `role_templates.json`, the LLM-provider catalog in `providers.default.json`, MCP tool groups in
  `mcp_group_rules.json`.

---

## Repository Layout

```
AgentSoftware/
├── pom.xml                          # Maven build (artifact com.maf:agent-software)
├── Containerfile                    # podman base image (Ubuntu 24.04) for role computers
├── docs/
│   ├── agent-system-multi-instance.md   # multiple AgentSystems in one process (analysis + guide)
│   └── llm-provider-manager.md          # LLM provider manager guide
├── src/main/
│   ├── java/com/agent/software/
│   │   ├── AgentSystem.java         # self-contained team system (clock + pool + dispatch + stores)
│   │   ├── Main.java                # main entry: full multi-day team simulation
│   │   ├── core/                    # Types (Event / AgentState / Priority), MCPServer (stdio JSON-RPC)
│   │   ├── event/                   # EventBus, TimeEventBus (time × event), EventDispatcher
│   │   ├── conversation/            # Conversation/ConversationManager — role ↔ LLM API dialogue state
│   │   │                            #   (per-day continuity, auto-compaction, shift-close, persistence)
│   │   ├── role/                    # AgentRole, RolePool, RoleLoader (JSON templates), RoleFactory, ToolRegistry
│   │   ├── computers/               # Computer, ComputerManager, PodmanComputer, SSHComputer (kind: podman|local|ssh)
│   │   ├── io/                      # Input / StdInput (console) / WebInput (Web page) — client replies
│   │   ├── llm/                     # LLM, OpenAICompatLLM (layered config, retries)
│   │   │   └── provider/            # ProviderManager + provider catalog (18 providers, 2 wire dialects)
│   │   ├── tools/                   # Tool / Toolkit base classes + registry
│   │   │   └── toolkits/            # memory, note, time, todo, taskview, pc, mcp, skill, email,
│   │   │                            #   hermes, talk, hr, client (one Toolkit + one Tool per function)
│   │   ├── services/MailService.java# company email: virtual mailbox / real SMTP (jakarta.mail)
│   │   ├── store/                   # ConfigStore, StateStore, NoteStore, TodoStore, PathManager (XDG)
│   │   ├── web/                     # ChatWebServer + ChatStore (zero-dependency Web UI, jdk httpserver)
│   │   ├── utils/Json.java          # Jackson helpers
│   │   └── demo/                    # RoleDemo / TalkDemo / McpDemo / WebDemo (lightweight demos)
│   └── resources/
│       ├── role_templates.json      # 55 role templates (role_id → role config)
│       ├── providers.default.json   # LLM provider catalog (OpenAI/Anthropic/Gemini/DeepSeek/…)
│       ├── providers.local.example.json
│       ├── mcp_group_rules.json     # MCP servers + tool groups (file_ops, git_ops, github_ops)
│       └── web/                     # static assets of the Web UI (index.html/app.js/style.css)
├── src/test/java/                   # JUnit 5 tests (196 tests / 27 classes)
└── data/                            # runtime data (gitignored)
    ├── computers/<role_id>/         # one host folder per role computer (mounted at /home/agent)
    ├── journals/                    # per-role activity journals
    ├── mail/  notes/  todos/  skills/  drive/  state.json
```

---

## Requirements

- **JDK 25+** and **Maven 3.8+** (`pom.xml` compiles with `maven.compiler.release=25`).
- An **OpenAI-compatible API key** (export `OPENAI_API_KEY`). Any compatible endpoint works —
  DeepSeek, vLLM, Ollama, LM Studio, … — by setting `OPENAI_BASE_URL` / `OPENAI_MODEL`
  (keyless local endpoints can omit the key).
- **Podman** for full simulations where every role gets its own container. Without podman,
  set the computer kind to `local` (see [Personal computers](#6-personal-computers)); the
  `Main` demo defaults to podman computers.

---

## Build & Test

```bash
mvn compile     # compile
mvn test        # run all JUnit tests (196 tests, 27 test classes)
mvn package     # produce target/agent-software.jar
```

---

## Running the Simulation

| Entry point | Class | What it does |
|---|---|---|
| Main simulation | `com.agent.software.Main` | The full default team (47 roles) over multiple days: restore saved progress → shift start → tasks → shift end → save. Starts the Web UI automatically and asks you for the project requirements on Day 1 (console prompt). |
| Role demo | `com.agent.software.demo.RoleDemo` | Creates one role from code (builder), assembles its tools and runs a real LLM task. |
| Talk demo | `com.agent.software.demo.TalkDemo` | A small team chatting through the `talk` tool (collaboration chain: coder → reviewer → architect). |
| MCP demo | `com.agent.software.demo.McpDemo` | Installs and calls MCP tools on a personal computer. |
| Web demo | `com.agent.software.demo.WebDemo` | Lightweight Web-UI demo (4 roles, no LLM/computers): shows group chat + a Client A conversation where **you reply in the browser**. |

Run any entry point through the exec plugin, e.g.:

```bash
# full multi-role simulation (recommended first run)
export OPENAI_API_KEY="sk-..."
mvn exec:java -Dexec.mainClass=com.agent.software.Main

# lightweight demos
mvn exec:java -Dexec.mainClass=com.agent.software.demo.RoleDemo
mvn exec:java -Dexec.mainClass=com.agent.software.demo.TalkDemo
mvn exec:java -Dexec.mainClass=com.agent.software.demo.WebDemo   # browser input; prints the URL
```

What a `Main` run looks like:

1. The system is assembled from the **47 default roles** (JSON templates) and progress is
   restored from `data/state.json` if it exists; otherwise it starts fresh on Day 1.
2. The **Web UI** starts at `http://127.0.0.1:8787/` (printed at startup).
3. Shortly after the 08:00:00 shift start (Tick 60 ≈ 08:01:00), the **CEO asks you for the
   project requirements** at the console prompt (with `WebDemo`, you type the reply on the Web
   page instead). Enter something like *"build a payment system for me"*.
4. Events unfold automatically: shift start, task assignment, tool calls, group chats,
   shift-end summaries. Interrupt with **Ctrl+C** — state is saved automatically and resumed
   on the next run.

---

## How a "Day" Works

The clock is a **simulated calendar clock** living in `event/TimeEventBus.java`. Day 1 is the
calendar date the run starts on (08:00:00); every simulated day after that is the next calendar
date. Key constants (default geometry = **1 Tick = 1 simulated second**):

| Constant | Default | Meaning |
|---|---|---|
| `secondsPerTick` (`SECONDS_PER_TICK`) | 1.0 | the Tick ↔ simulated-time conversion (simulated seconds per tick); changing it rescales the geometry below |
| `SIM_SECONDS_PER_SHIFT` | 36000 | the 10 h shift (08:00:00 → 18:00:00) in simulated seconds |
| `SIM_SECONDS_PER_DAY` | 86400 | a full calendar day (08:00 → next 08:00) in simulated seconds |
| `shiftStartTick` | 0 | tick 0 of each day ⇔ 08:00:00 (`SHIFT_START` fires) |
| `shiftEndTick` | 36000 | shift ends at tick 36000 ⇔ 18:00:00 (`SHIFT_END` fires) |
| `ticksPerDay` | 86400 | day cycle: 86400 ticks = 24 h at 1 s/tick (the after-hours window is skipped by the rollover jump) |
| `taskTickMin` / `taskTickMax` | 0 / 36000 | note reminders can only be scheduled inside the shift (08:00:00–18:00:00) |
| `simSecondsPerRealSecond` (`SIM_SECONDS_PER_REAL_SECOND`) | 1.0 | busy-clock speed: simulated seconds per real second of team work (1.0 = real-time flow) |
| `WRAP_UP_GRACE_SECONDS` | 600 | real seconds the clock waits for the daily wrap-up before forcing the rollover |
| `FAST_FORWARD_IDLE_SECONDS` | 60 | clock jumps when **all** roles have been idle ≥ 60 s |

Examples at the default conversion: tick 0 → `08:00:00`, tick 21600 → `14:00:00`, tick 36000 →
`18:00:00`, tick 86400 → the next day's `08:00:00`.

How the clock moves (event-driven, not real-time):

1. **Busy flow.** While at least one role is working (LLM round, tool call, talk wait), the clock
   advances at the busy-clock speed — with the default real-time pacing, a task that really takes
   40 s advances the simulated clock by 40 seconds (a task started at 14:23:10 may genuinely
   finish at 14:23:50). Busy work can never push the clock past 18:00:00: anything still queued
   at shift end is **held and carried over to the next day** (off-duty roles do not start new
   ordinary work after 18:00).
2. **Idle fast-forward.** When the whole team has been idle for `FAST_FORWARD_IDLE_SECONDS`, the
   clock jumps to the next scheduled event Tick (a note reminder / the 18:00:00 shift end / …) —
   nobody waits in real time and a busy LLM never "misses" a deadline.
3. **Wrap-up & day rollover.** At 18:00:00 `SHIFT_END` fires: roles synchronously stuck in a
   `talk` wait are woken first (otherwise they would never reach their summary task), then every
   role writes its end-of-day summary and goes `OFF_DUTY`. Once **all** roles have wrapped up and
   the team is idle, the clock rolls over to the **next calendar day at 08:00:00** (`SHIFT_START`
   fires again) and the loop repeats. If a role's summary failed and the wrap-up stalls, the time
   manager forces the rollover after `WRAP_UP_GRACE_SECONDS` instead of deadlocking.

Work-rest events fire automatically: `SHIFT_START` (08:00:00), `SHIFT_END` (18:00:00). Scheduled
notes with a reminder (`write_note` with `remind_tick`, ticks 0~36000 ⇔ 08:00:00–18:00:00) are
registered on the same event schedule and fire a reminder event when due — notes and scheduled
tasks are one unified concept. The simulated date/time (calendar date + `HH:MM:SS`) is shown by
`get_time`, in the shift-event payloads the roles read, on the Web UI header, and in journal/
console output.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│ AgentSystem (one per "company", self-contained)              │
│  owns: TimeEventBus · RolePool · EventDispatcher             │
│        ComputerManager · MailService · MCPManager            │
│        SkillManager · clientLock · ChatStore · dataDir       │
└───────────────┬──────────────────────────────────────────────┘
                │ SHIFT_START / SHIFT_END / TASK_DUE / NEW_MAIL / custom events
                ▼
┌──────────────────────────────────────────────────────────────┐
│ EventDispatcher.trigger(event) → fan-out to every role       │
│ per-role 3-layer filter: state mask → salience → wake        │
└───────────────┬──────────────────────────────────────────────┘
                │ accepted events become Tasks
                ▼
┌──────────────────────────────────────────────────────────────┐
│ RolePool — one virtual thread per role, priority queue       │
│ (CRITICAL > HIGH > NORMAL > LOW)                             │
└───────────────┬──────────────────────────────────────────────┘
                │ AgentRole.executeWithTools: LLM → tool_calls → execute → feedback
                ▼
┌──────────────────────────────────────────────────────────────┐
│ AgentRole's personal computer (podman container)             │
│  · MCP servers run inside the container (podman exec)        │
│  · default MCP group file_ops auto-installed at role setup   │
│  · tools: notes/time/todo/taskview/pc/mcp/skill/email/talk…  │
│  · talk (same group) / email (cross group) / talk_to_client  │
└──────────────────────────────────────────────────────────────┘
```

---

## Core Features

### 1. Role templates & the default team

All role definitions are JSON, not code: `role_templates.json` maps `role_id → role config`
(name, pinyin `username`, title, responsibilities, personality, skills, interest keywords,
group, …) and is loaded into `RoleLoader.TEMPLATES` at class-load time. 55 templates exist;
`RoleLoader.DEFAULT_ROLES` selects the **47-role default team** used by `Main`:

- **Leadership Group**: CEO Lin Zong, COO Chen Zong, HR Wang Renshi, CTO Gao Yuan,
  business analyst Xu Ruonan
- **Leads & release**: frontend/backend/mobile/fullstack/test leads, architect Wang Jianguo,
  release manager Fang Jinyan
- **Engineering**: frontend_dev_1–3, backend_dev_1–3, mobile_dev_1–3, fullstack_dev_1–3
- **Testing**: tester_1–20 · **Security**: attacker_1–3

Other templates (CFO Qian Cai, reviewer, qa_engineer, ops_engineer, content_marketer,
data_analyst, support_agent, …) stay available through the `RoleLoader` / `AgentSystem` API.
Roles can also be built in code with the `AgentRole.builder()` fluent API.

### 2. AgentRole & RolePool

- Every role is an `AgentRole`: a task queue, a state machine (`ON_DUTY` / `OFF_DUTY` / `WAIT` /
  wrapping-up), its own LLM client, its own stores (notes/todos) and its own activity journal.
- `RolePool` schedules roles: one resident **virtual thread** per role, a priority task queue
  (CRITICAL > HIGH > NORMAL > LOW), dynamic onboarding (`addRoleAndStart` — hire and start
  immediately) and removal.
- The `talk` toolkit is auto-registered when the pool starts; default toolkits are wired by
  `RolePool.setupRole` (see §4).
- Tasks execute as an LLM tool loop (`executeWithTools`): native function calling with
  `tool_choice:"auto"`, results fed back with `role:"tool"` + `tool_call_id`, and a bounded
  number of tool-call rounds per task. Trace messages (chain of thought, tool calls, final
  answer) are recorded and surfaced in the Web UI.

### 3. The 3-layer event filter

During dispatch each role evaluates every event independently — low-value events are intercepted
at **zero tokens**:

| Layer | Mechanism | Token cost |
|---|---|---|
| 1 — State mask | `OFF_DUTY` roles ignore non-EMERGENCY events | 0 |
| 2 — Salience | keyword hits + urgency weighting (`priority*0.4 + relevance*0.6`) | 0 |
| 3 — Wake | events that pass become queued Tasks for that role | on demand |

System-time events (`SHIFT_START` / `SHIFT_END`, `source="time"`) bypass Layer 2 and go straight
through. Inside an `AgentSystem`, mail delivery additionally dispatches a **targeted**
`email/NEW_MAIL` event to the recipient role, which queues a *"You have a new email from X"*
task — without disturbing off-duty roles.

### 4. Default tools (template style)

One `Toolkit` subclass per domain, one `Tool` subclass per function
(`tools/` + `tools/toolkits/**`), registered through `ToolRegistry` and exposed to the LLM as
OpenAI-style function schemas. `Toolkits.defaultToolkits(role)` auto-assembles for every role
(when `autoToolkits=true`):

| Toolkit | Tools | Purpose |
|---|---|---|
| `memory.Memory` | `summary` | end-of-day summary (memory + triggers power-off) |
| `note.Note` | `write_note` `edit_note` `list_notes` `read_note` `delete_note` | notes; `write_note` with a reminder = scheduled event |
| `time.Time` | `get_time` `take_rest` | work-rest schedule |
| `todo.Todo` | `todo_add` `todo_list` `todo_update` `todo_delete` | personal todo list |
| `taskview.TaskView` | `my_tasks` | the role's task queue + history |
| `pc.Pc` | `run_command` `computer_status` `lan_devices` `reboot` | operations on its own computer |
| `mcp.McpManager` | `mcp_search` `mcp_list` `mcp_add` `mcp_remove` `mcp_my_tools` | self-service MCP tool management |
| `skill.Skill` | `skill_search` `skill_list` `skill_add` `skill_remove` `skill_my_skills` | SKILL.md skill library |
| `email.Email` | `send_email` `read_mail` `open_mail` `mail_address_book` | company email |
| `client.Client` | `talk_to_client` | talking to you, the client (Leadership Group members) |
| `hr.Hr` | `post_job_posting` `list_candidates` | hiring (HR; added explicitly in `Main`) |
| `talk.Talk` | `talk` `list_roles` | inter-role chat, auto-registered at pool start |

Exclusive/conditional wiring: the CEO (and the rest of the Leadership Group) can reach the
client; HR can post jobs. `hermes.Hermes` (`hermes_new_conversation` / `hermes_send`) is also
available for calling a Hermes agent conversationally.

### 5. Memory & journals

- **Notes** are the external memory of a role (they live in its own computer / note store) and
  can carry a `remind_tick` → they become a scheduled event, like a task.
- **Daily summaries**: at shift end each role calls `summary`; the next day
  `buildSystemPrompt()` injects the most recent summary as `[Yesterday's Summary]` so the role
  can continue its work.
- **Journals**: every role has an activity journal under `data/journals/<role_id>.md`
  (gitignored) recording tasks, tool calls, messages, WAIT transitions and accepted/skipped
  events — useful to review the whole team's activity in one place.

### 6. Personal computers

- `ComputerManager` creates a `Computer` per role: **podman** (default) | `local` | `ssh`.
  Without podman installed, podman computers throw at construction — use `local` for plain
  directory-based simulation.
- Podman computers run as containers named `maf-<role_id>` on the custom bridge network
  `maf-net`. The base image `maf-base:latest` is defined by the root `Containerfile`
  (Ubuntu 24.04 with Aliyun mirrors, Node 22 LTS, sudo/git/python, and the MCP servers
  preinstalled: the official filesystem server + Microsoft `@playwright/mcp` browser
  automation with chromium baked in) and is built automatically on first use.
- The role's host folder `data/computers/<role_id>` is mounted at the container's `/home/agent`
  (the same files visible from both sides, with a pinyin `username` and a stable uid per
  employee). A shared corporate **cloud drive** is mounted at `/mnt/drive` (`Public` + per-
  employee directories).
- Computers are **powered off at shift end right after the daily summary is saved** and started
  again when work resumes. MCP servers run *inside* the container via `podman exec`; each
  computer automatically starts two stdio sessions — the filesystem server (file tools) and the
  Playwright browser server `@playwright/mcp` (headless chromium, `browser_*` tools) — and their
  tools show up in `mcp_search`/`mcp_list` for the role to install via `mcp_add`. Session
  liveness is probed at each shift start so servers are rebuilt automatically after a stop.

### 7. Company email

Each employee gets `<username>@<suffix>` (default suffix `company.com`; override per role with
an explicit `email` field). Default is a **virtual mailbox** persisted under `data/mail/`;
setting `SMTP_HOST` switches delivery to **real SMTP** (jakarta.mail) while still keeping an
internal copy for the simulated intranet. SMTP failures return an error instead of silently
dropping mail.

### 8. Groups, talk & hiring

- Every role belongs to a **group** (see the template JSON). `talk` is restricted to same-group
  members; cross-group communication goes through email. New hires are ungrouped and can talk to
  anyone.
- **Hire-to-onboard**: HR calls `post_job_posting` → `RoleFactory` generates a new role from a
  template (unique name from the name pool) → the hire is added with `addRoleAndStart` and
  **immediately starts working** with the full default toolkit + MCP file tools + its own
  computer.

### 9. Persistence

`StateStore` aggregates all serializable state into a single JSON file (default
`data/state.json`, atomic writes): role profiles, task history, incomplete (queued) tasks,
computer/container bindings (existing containers are re-bound, not rebuilt) and the clock
(day / tick of day / the simulated calendar `base_date` of day 1). `Main` auto-saves on exit and
auto-restores on startup, so a simulation can resume from where it stopped — with the same
calendar dates.

### 10. LLM layer & provider manager

- `OpenAICompatLLM` talks to any **OpenAI-compatible** endpoint over the JDK `HttpClient`, with
  layered config resolution: explicit constructor args → Java args (`-D…`) → environment
  variables → config file (`llm.*` keys) → defaults (`https://api.openai.com`, `gpt-4o-mini`).
- `llm.provider.ProviderManager` bundles a **provider catalog** (`providers.default.json`, 18
  providers: OpenAI, Anthropic, Google Gemini, DeepSeek, Mistral, Groq, OpenRouter, Together,
  xAI, Moonshot, Zhipu, DashScope, SiliconFlow, Cerebras, NVIDIA, Ollama, vLLM, LM Studio) with
  base URLs, request paths and the wire dialect each speaks (OpenAI or Anthropic). It merges in
  an optional local `providers.json` (see `providers.local.example.json`; set
  `LLM_PROVIDERS_CONFIG` or `-Dllm.providers.config` to point at it) and can list models through
  each provider's `/models` endpoint, normalizing both dialects into `ModelInfo`. See
  [docs/llm-provider-manager.md](docs/llm-provider-manager.md).

### 11. Web UI & client input channels

A zero-dependency Web UI (`ChatWebServer` on the JDK `com.sun.net.httpserver`, static assets in
`src/main/resources/web/`) starts automatically with `Main` and can also be run standalone via
`WebDemo`. Open the printed URL (default `http://127.0.0.1:8787/`):

- **Left** — channels: the top **All Activity** feed shows *every* role's chain of thought,
  tool calls and final outputs; below it each group (Leadership Group, Frontend Development
  Group, …) shows its member count and an unread badge.
- **Right** — messages rendered by kind: 💬 `talk` / `client` chat bubbles, 🧠 `reason`
  (chain of thought from the LLM's `reasoning_content`), ✎ `note` narration, 🛠 `tool` call
  cards (name + arguments + output), ✔/✗ `answer` (final task result). Leadership Group chats
  include your own (Client A) conversations.
- **Input box**: enabled only when the Leadership Group is selected *and* a member is currently
  waiting for your reply via `talk_to_client`; type your answer and press Enter.

The client's reply channel is decided by the `Input` passed to the `AgentSystem` at creation:
`StdInput` (console prompt) or `WebInput` (page input box, default 20-minute reply timeout,
returns an error instead of blocking when no browser is attached).

HTTP API (polled by the frontend, no auth):

| Endpoint | Description |
|---|---|
| `GET /api/state` | group roster + clock (day / tick / date / time / describe) + Client A conversation state (`clientTalk.active`) |
| `GET /api/messages?since=N` | incremental messages with seq > N |
| `POST /api/reply` `{"text": "…"}` | submit a Client A reply (409 unless someone is waiting) |
| `POST /api/attach` | Web attach heartbeat |

Message kinds: `talk`, `client`, `reason`, `note`, `tool`, `answer`; trace messages carry
structured `extra` metadata (`tool`: `{tool, args, result, round, taskId}`; `answer`:
`{status: done|failed, tokens, taskId}`). Implementation: `web/ChatStore.java` (storage +
Client A coordination) + `web/ChatWebServer.java`; tests in `ChatStoreTest` /
`ChatWebServerTest` / `TalkToClientWebTest` / `AgentRoleTraceTest`.

### 12. Tool & MCP management

- `ToolRegistry` is the unified registry: registering a `Tool` / `Toolkit` immediately makes it
  callable by the LLM (OpenAI-style schemas auto-generated from flat parameter descriptions).
- **MCP self-service**: `McpManager` (per system) + the `mcp` toolkit let roles search, install
  and remove MCP tool groups (`mcp_group_rules.json` defines `file_ops`, `git_ops`,
  `github_ops`, `default`). The default `file_ops` group (filesystem read/write/edit over the
  MCP stdio client implemented in `core/MCPServer.java`) is auto-installed at role setup.
- **Skills**: `SkillManager` + the `skill` toolkit manage SKILL.md entries in the shared skill
  library.

### 13. Role ↔ LLM API conversation management

Each role now runs a managed **dialogue with its LLM API** (`conversation/Conversation.java` +
`conversation/ConversationManager.java`), sitting exactly between `AgentRole` and `OpenAICompatLLM`:

- **Cross-task continuity.** Previously every task started from an empty message list (system
  prompt + the task description), so a role could not remember within a day what it had just
  discussed or done with the model. Now `executeWithTools` prepares each request as
  *system prompt + committed conversation history + new task*, and when a task completes its
  exchange (user task + final answer, enriched with a bounded recap of the tool calls the role
  made) is committed back to the day dialogue — the next task builds on it.
- **Automatic context compaction.** Each conversation has a character budget
  (`Conversation.DEFAULT_MAX_HISTORY_CHARS`, 24 000 by default; the budget is a per-conversation
  constructor argument). When the committed history crosses it, the whole history is summarized
  into one compact message via `LLM.summarize` (one extra API call per budget crossing); if the
  summary call fails (or no LLM is at hand), the oldest messages are dropped/truncated instead —
  the context always shrinks, never grows without bound.
- **Shift lifecycle.** Conversations are keyed by work day. When a role writes its end-of-day
  summary and goes `OFF_DUTY`, the `summary` tool calls `Conversation.closeDay()`: the day
  dialogue is cleared (its recap is already persisted in the day's summary file) and the trailing
  "summary saved" exchange is not appended. A new shift start — or a same-day EMERGENCY task
  after off-duty — reopens the conversation with a clean, context-flushed slate; the next day's
  cold start still comes from `[Yesterday's Summary]` in the system prompt.
- **Persistence & isolation.** Open day dialogues are archived with the rest of the role state by
  `StateStore` (`conversation` field per role, restored on the next start), so an interrupted run
  resumes mid-dialogue. Each `AgentSystem` owns its own `ConversationManager`, so multiple systems
  in one process keep their role dialogues fully isolated.

One `Conversation` exists per role (keyed by `role_id`), accessed through `AgentRole.conversation()`;
tool results are recapped at most `TOOL_RECAP_LIMIT` per task and `TOOL_RECAP_RESULT_MAX` chars
each. Tests: `conversation/ConversationTest`, `conversation/ConversationEndToEndTest`,
`conversation/ConversationStateStoreTest`.

---

## Configuration Reference

All of these can be provided as environment variables **or** as Java args
(`-D<same name>`, which win over env vars). LLM values can additionally be placed in the
ConfigStore file (`<config dir>/config.json`, dot keys `llm.api_key` / `llm.base_url` /
`llm.model`); precedence: constructor args > `-D` system properties > env vars > config file >
defaults.

| Variable (env / `-D`) | Default | Description |
|---|---|---|
| `OPENAI_API_KEY` | *(empty)* | API key for the OpenAI-compatible endpoint (omitted → no `Authorization` header; fine for keyless local endpoints) |
| `OPENAI_BASE_URL` | `https://api.openai.com` | base URL of any OpenAI-compatible API (DeepSeek / vLLM / Ollama / …) |
| `OPENAI_MODEL` | `gpt-4o-mini` | model name |
| `LLM_PROVIDERS_CONFIG` | *(see doc)* | path of a local providers JSON for `ProviderManager` (overrides/extensions to the catalog) |
| `MAIL_SUFFIX` | `company.com` | company email domain suffix (→ `guoxiaodong@company.com`) |
| `MAIL_DATA_DIR` | `data/mail` | where virtual mailboxes are persisted |
| `SMTP_HOST` | *(empty)* | SMTP server; **setting it switches mail from virtual to real sending** |
| `SMTP_PORT` | `587` | SMTP port (465 auto-uses SSL) |
| `SMTP_USER` / `SMTP_PASSWORD` | *(empty)* | SMTP credentials (optional) |
| `SMTP_FROM` | `SMTP_USER` or sender | real-mail sender address (optional) |
| `SMTP_USE_SSL` | by port | force SSL (`true`) or non-SSL (`false`) |
| `AGENTSOFTWARE_WEB_HOST` | `0.0.0.0` | Web UI listen address |
| `AGENTSOFTWARE_WEB_PORT` | `8787` | Web UI port |
| `AGENTSOFTWARE_CLIENT_REPLY_TIMEOUT` | `1200000` (20 min) | Client A reply timeout in Web-input mode (ms) |
| `AGENTSOFTWARE_SECONDS_PER_TICK` | `1.0` | Tick ↔ simulated-time conversion: simulated seconds that one tick represents (default 1 Tick = 1 simulated second; rescales the shift/day tick geometry) |
| `AGENTSOFTWARE_SIM_SECONDS_PER_REAL_SECOND` | `1.0` | busy-clock speed: simulated seconds that pass per real second while at least one role is working (`0` freezes the clock while busy) |
| `AGENTSOFTWARE_DATA_DIR` / `_CONFIG_DIR` / `_CACHE_DIR` / `_LOG_DIR` | XDG dirs | path overrides for data / config / cache / log directories (PathManager, app prefix `AgentSoftware`) |

Note: the simulation runtime of an `AgentSystem` roots its own files under its `dataDir`
(default `./data`), while `PathManager` XDG-style directories are used for config/log/cache
lookups such as the ConfigStore file.

---

## Multiple AgentSystems in One Process

Each `AgentSystem` directly owns its collaboration objects — clock, computer registry, mailbox,
MCP/skill managers, Client A communication lock, chat storage — plus its own data directory, so
several systems can safely coexist in one JVM (full analysis in
[docs/agent-system-multi-instance.md](docs/agent-system-multi-instance.md)):

```java
import com.agent.software.AgentSystem;
import com.agent.software.io.StdInput;
import java.nio.file.Paths;
import java.util.List;

AgentSystem companyA = new AgentSystem(Paths.get("data/company-a"), null,
        List.of("CEO", "COO", "HR"), 30.0, true, new StdInput());
AgentSystem companyB = new AgentSystem(Paths.get("data/company-b"), null,
        List.of("CEO", "COO", "HR"), 30.0, true, new StdInput());
```

Still process-wide on purpose: the role-template registry, default LLM config and host-level
podman infrastructure (networks / base images / container names). Two podman-based systems on the
same host must therefore use disjoint role sets — or `local` computers, each rooted in its own
`base_dir`.

---

## Documentation

- [docs/agent-system-multi-instance.md](docs/agent-system-multi-instance.md) — making
  `AgentSystem` self-contained; running multiple systems in one process.
- [docs/llm-provider-manager.md](docs/llm-provider-manager.md) — the LLM provider manager,
  catalog and local provider files.
