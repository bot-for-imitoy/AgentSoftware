# Evaluation Report: AgentSystem Single-Instance Limitation and Multi-Instance Refactor

> Conclusion first: the current codebase can **only run one `AgentSystem` safely within a single JVM process**.
> The root cause is not `AgentSystem` itself, but the set of **process-level global singletons / static mutable state /
> global file paths** it depends on. If a second `AgentSystem` is created, the two systems share these global resources, causing
> roles and computers to overwrite each other, clock-event crosstalk, mail/note/journal and other data files to overwrite each other, and other
> **unpredictable anomalies**. This document evaluates the problems and presents the refactor plan to be implemented per this report.

---

## 1. Problem List: Why "Only One AgentSystem Is Allowed"

### P1. Process-level global singletons (service objects shared across systems)

| Location | Global object | Shared content | Consequences when two systems coexist |
|---|---|---|---|
| `computers/ComputerManager.java:44` | `INSTANCE` (role computer registry) | `role_id → Computer` mapping, `role_id → person name` mapping | ① registering the same `role_id` in the second system **overwrites** the first system's computer object; ② one system dismissing/destroying a computer deletes the other system's container; ③ `lan_devices` lists roles from both systems mixed together; ④ archive restore binds to the wrong computer |
| `tools/Toolkits.java:42,45` | `MCP_MANAGER` / `SKILL_MANAGER` | MCP tool installation records (`role_id → Set<tool names>`), skill library installation records | two systems with the same `role_id` share the "installed tools" bookkeeping; `mcp_my_tools` / `skill_my_skills` pollute each other; the skill library's default directory `data/skills` is also shared globally |
| `services/MailService.java:429` | `mailService` lazy-loaded singleton | all mailboxes (mailboxes in-memory table + `data/mail/mailboxes.json`) | mail is fully shared between the two systems (system A sends, system B's inbox sees it too), and archives overwrite each other |
| `tools/toolkits/client/ClientCommunicationLock.java:15` | `INSTANCE` (mutex lock for Client A conversations) | lock holder | once a system A member holds the lock, system B members cannot talk to Client A (cross-system mutual blocking) |
| `event/TimeEventBus.java:580` | `getDefaultBus()` process-level default clock | shared tick/day state and event dispatch table | `AgentRole.timeManager()` **silently falls back to the process-level default clock** when not explicitly bound; unbound roles from both systems share the same clock, ticks/days jump for each other, and scheduled events crosstalk |

### P2. Static mutable registries / counters

| Location | Static state | Consequences |
|---|---|---|
| `role/RoleLoader.java:84` | `TEMPLATES` global template registry (mutable) | `RoleFactory` hiring (HR tool) registers new role templates into the global table; roles hired by system A appear in system B's template pool |
| `role/RoleLoader.java:305-306` | `usedNames` / `namePoolInitialized` | global name allocator, shared across systems (minor impact, but still process-level shared state) |
| `role/AgentRole.java:50` | `JOURNAL_DIR` (static, can be rewritten by tests) | two systems with the same `role_id` append activity journals to the **same file** |

### P3. Global file paths (single data directory)

| Location | Path | Problem |
|---|---|---|
| `role/AgentRole.java:50` | `data/journals/` | two systems with the same `role_id` write the same journal file |
| `store/NoteStore.java:41` | `PathManager` user directory `~/.local/share/AgentCompany/notes` | ① inconsistent with the rest of the project's `./data/*` layout (.gitignore explicitly lists `data/notes/`); ② two systems with the same `role_id` share the notes directory |
| `store/TodoStore.java:35` | `./data/todos/<role_id>.json` | two systems with the same `role_id` share the same to-dos |
| `services/MailService.java:39` | `data/mail/mailboxes.json` | one mail archive shared globally |
| `store/StateStore.java:41` | `./data/state.json` | the two systems' save/restore overwrite each other (the later save overwrites the earlier one) |
| `computers/Computer.java:34-35` | `./data/computers`, `./data/drive` | local simulated computer directories/cloud drive shared globally |
| `tools/toolkits/skill/SkillManager.java:90` | `data/skills` | skill library shared globally |

### P4. Roles/tools directly pull global singletons

`AgentRole` and the various tool classes call the global singletons directly **without dependency injection**:

- `AgentRole.computer()` → `ComputerManager.getInstance().create(...)`
- `AgentRole.mailAddress()` → `MailService.getMailService().emailFor(this)`
- `AgentRole.timeManager()` → falls back to `TimeEventBus.getDefaultBus()` when unbound
- `RolePool.setupRole()` → `Toolkits.getMcpManager().installGroupDefaults(...)`
- `RolePool.removeRole()` → `ComputerManager.getInstance().destroy(roleId)`
- `toolkits/pc/LanDevices` → `ComputerManager.getInstance().listLanDevices()`
- `toolkits/email/Email` → falls back to `MailService.getMailService()` by default
- `toolkits/client/TalkToClient` → falls back to `ClientCommunicationLock.getInstance()` by default
- `store/StateStore` → `ComputerManager.getInstance().create/nameOf`

### P5. Defects found along the way (unrelated to multi-instance; fixed together)

- `NoteStore`'s default directory goes through `PathManager` (user home directory `~/.local/share/AgentCompany/notes`),
  which contradicts the project's `./data/*` layout and the `data/notes/` convention in `.gitignore`; in a read-only home directory
  environment, tests fail outright (6 test cases failed for this reason in this repo's sandbox).
- The environment variable prefix `AGENTSCHEDULER_DATA_DIR` used by tests and the README is inconsistent with the prefix that `PathManager`
  actually derives (`appName="AgentCompany"` → `AGENTCOMPANY_DATA_DIR`),
  so the data-directory property set in tests never takes effect.

---

## 2. Concrete Examples of Anomalies When Two AgentSystems Coexist

1. **Role registration conflict**: `RolePool.addRole` throws for a duplicate
   `role_id` with `IllegalArgumentException("Role 'xxx' already exists")`; even though each system holds its own
   independent `RolePool`, the computer registry still overwrites each other because `ComputerManager` is a global singleton.
2. **Clock crosstalk**: a system A role goes on duty at Tick 30; system B's unbound roles fall back to
   `TimeEventBus.getDefaultBus()`, and the two systems' shift events trigger each other on the same clock line.
3. **Data overwrite**: system A's and system B's `StateStore` both write `./data/state.json`; the later save
   overwrites the earlier one; the notes/to-dos/mail/journals of the same `role_id` in both systems write the same set of files.
4. **Mutex lock blocking across systems**: while system A's CEO is talking to Client A, all members of system B
   have `talk_to_client` rejected.
5. **Container-deletion accident**: when system A dismisses an employee, it deletes the container of the same-named
   role in system B with `podman rm -f` via the global `ComputerManager`.

---

## 3. Refactor Plan (Implemented per This Report)

Core idea: change "process-level global singletons" into "one instance per AgentSystem", held directly by AgentSystem.
No additional packaging classes are introduced: `AgentSystem` creates and directly holds its own set of
collaboration objects and data root directory at construction time, explicitly injected via `RolePool → AgentRole → tool classes`; one system
can run independently, and multiple systems do not interfere with each other.

### 3.1 `AgentSystem` Self-Contains Collaboration Objects and Data Directory

New direct fields on `AgentSystem` (all per-system independent instances; multiple instances do not interfere):

- `timeManager` (TimeEventBus) — one clock per system (existing)
- `configStore` (ConfigStore)
- `computerManager` (ComputerManager) — one role computer registry per system
- `mailService` (MailService) — one mailbox set per system (data under `dataDir/mail`)
- `mcpManager` (MCPManager) / `skillManager` (SkillManager) — one per system
- `clientLock` (ClientCommunicationLock) — one Client A conversation lock per system
- `chatStore` (ChatStore) — one chat store per system (data source for the Web UI)
- `dataDir` (Path) — per-system data root directory, default `./data`

Derived data directories (all rooted at `dataDir`): `journalDir/notesDir/todosDir/mailDir/
computersDir/driveDir/skillsDir/stateFile`.

Multi-instance usage: pass a different `dataDir` to each `AgentSystem` for complete file-level isolation:
`new AgentSystem(Paths.get("data/company-a"), null, roleIds, 30.0, true)`.

### 3.2 Module Changes

| File | Change |
|---|---|
| `AgentSystem` | Add direct fields `computerManager/mailService/mcpManager/skillManager/clientLock/chatStore/dataDir` created at construction time; add an `AgentSystem(Path dataDir, ...)` overload (original signature stays compatible); `addRoles` unconditionally binds `bindTimeManager` + `bindSystem(this)`; add accessors for each data directory |
| `RolePool` | Add a constructor overload carrying `AgentSystem owner` (may be null = standalone role pool); `setupRole` binds `bindSystem`; default MCP groups use `role.mcpManager()`; `removeRole` uses `role.computerManager()`; `newLlm` uses `owner.configStore` |
| `AgentRole` | Add a `system` field and `bindSystem()/system()`; add `computerManager()/mailService()/clientLock()/mcpManager()/skillManager()/chatStore()` resolution helpers (use the system instance when owned by a system, otherwise fall back to global defaults to keep legacy usage compatible); `computer()/mailAddress()/noteStore()/todoStore()/journal()` all go through the owning system |
| `Toolkits.defaultToolkits` | Build tool classes with `role.mcpManager()/skillManager()/mailService()` (roles inside a system get that system's instances) |
| `toolkits/client/Client` + `TalkToClient` | Use `role.system().clientLock` / `.chatStore` (unbound systems fall back to global defaults / no chat store) |
| `toolkits/talk/TalkTo` | `recordTalk` uses `role.system().chatStore` |
| `web/ChatWebServer` + `WebDemo` | Use `system.chatStore` instead |
| `store/NoteStore` | Change the default base directory to `data/notes` (consistent with .gitignore and the project-wide layout; explicit parameter behavior unchanged) |
| `store/StateStore` | `collect/restoreComputers` use `system.computerManager` |
| `computers/ComputerManager` | Make the constructor public (allow `new` per system); keep `getInstance()` as the default fallback |

### 3.3 Process-Level Sharing Kept (Intentional and Documented)

- **`RoleLoader.TEMPLATES` / name pool**: role templates are the "company organizational structure definition" on the classpath,
  a process-level shared registry; new templates registered by HR hiring are visible within the process (consistent with single-system semantics). For isolation,
  a per-system template table could be added to `AgentSystem` (not in this iteration).
- **podman network / base image / container names**: the `maf-net` network, `maf-base:latest` image,
  and `maf-<role_id>` container names are host-level infrastructure shared across systems (created idempotently).
  **Multi-instance deployment constraint**: if two AgentSystems on the same host use podman computers with the same `role_id`,
  the container names will conflict; multi-instance scenarios should use `local` computers (with per-system
  `base_dir`/`drive_dir`) or non-overlapping role sets.
- **`ConfigStore` default config path**: process-level configuration such as LLM (`~/.config/AgentCompany/config.json`)
  stays shared (environment variables take precedence; the config is read-only in nature); all data-class state is isolated per system.

### 3.4 Compatibility

- All existing public constructors and fields of `AgentSystem` / `RolePool` / `AgentRole` are kept;
  `Main.java`, demo programs, and existing tests need no structural changes (only Web-related tests change to access
  `system.chatStore` directly).
- Single-system usage behaves the same (data stays under `./data/*`); only `NoteStore`'s default directory moves from the user home
  to `data/notes`.
- Standalone roles/pools not bound to a system (demos, unit tests) continue to fall back to global defaults, as before the refactor.

---

## 4. Verification

- `mvn test` all green (including the multi-instance isolation test `AgentSystemIsolationTest` and the Web tests).
- New tests cover: two systems' roles/clocks/computer registries/mailboxes/mutex locks/chat stores/data directories do not
  interfere; the same `role_id` is independent across the two systems; standalone roles not bound to a system fall back to global defaults.

## 5. Follow-Up Suggestions (Out of Scope for This Iteration)

- If full "multiple companies in one process"-level isolation is needed, `RoleLoader.TEMPLATES` and `ConfigStore`
  could also become `AgentSystem`-owned.
- podman container names support a per-system prefix (e.g. `maf-<system>-<role_id>`), eliminating container-name conflicts
  for multiple instances on the same host.
- Add `close()`/resource-reclamation semantics to `AgentSystem` so the lifecycles of multiple systems can be managed independently.
