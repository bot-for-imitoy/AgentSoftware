# Design critique — branch `refactor` @ `6ce76e6`

Scope: `src/main/java/com/agent/software/` (12,323 LOC, 74 files). Evidence is file:line against the current working tree.
Measurements used: `wc -l`, count of `public`/`protected` members, field counts, import graph, grep for call sites.

---

## 1. God classes / too many responsibilities

**[high] `Application` is a 642-line composition root that also persists, serializes the HTTP API, builds prompts and hires roles — `app/Application.java:82-642`**
Evidence: 642 lines; 18 instance fields (`:84-104`); 35 public members (`:149-608`); a ~105-line static `create` method (`:159-265`); and it additionally owns persistence (`saveState` `:419-430`, `restoreState` `:433-458`, `toSnapshot` `:464-469`, `toTask` `:471-483`, `parseState` `:485-491`), the web DTO assembly (`stateMap` `:360-381`, `groups` `:383-409`), the system prompt (`systemPrompt` `:612-641`), and four role-hiring variants (`:502-535`).
Why it hurts: every new feature touches this one class; the constructor takes 16 positional arguments of 10 different types (`:119-127`), so any wiring change is a ripple edit. It cannot be tested without constructing the whole world.
Direction: split into `Bootstrap` (wiring only), `StateService` (snapshot/restore), `SystemPromptBuilder`, and a `WebStateView` DTO mapper.

**[high] `computers/Computer.java` is a 458-line class hierarchy exported as one abstract class with 57 public members — `computers/Computer.java:29-458`**
Evidence: public mutable fields `roleId`/`name`/`username`/`uid`/`stdout`/`stderr`/`returnCode` (`:41`, `:149-151`, `:332-334`); it mixes process execution (`:161-224`), MCP session liveness/reconnect (`:57-104`), MCP tool registry (`:246-300`), path/workdir logic (`:233-245`, `:313-330`) and three concrete subclasses in the same file (`LocalComputer` `:331`, plus `PodmanComputer` 453 LOC and `SSHComputer` 136 LOC).
Why it hurts: this class was supposedly replaced by `ComputerPort`, but it is still the real implementation; its public mutable fields are read across adapters, and its MCP lifecycle is entangled with process execution.
Direction: delete the abstract class; make `PodmanComputer`/`SSHComputer`/`LocalComputer` implement `ComputerPort` directly and move MCP session state into an `McpSession` collaborator.

**[med] `adapters/llm/provider/ProviderManager.java` (640 LOC, 24 public members) and `adapters/llm/RetryArbiter.java` (377 LOC) are god classes in the adapter layer**
Evidence: `ProviderManager` 21 `Map<String,Object>` sites, 6 `@SuppressWarnings("unchecked")` (`:408,476,512,522,558,613`). `RetryArbiter` mixes a process-global registry (`:72`), a priority `Comparator` (`:127`), waiter bookkeeping (`:132`) and HTTP retry timing.
Why it hurts: catalog parsing, model discovery, routing and retry ordering are one blob; neither is reachable behind a port, so `Application.buildLlm` (`app/Application.java:267-275`) reaches into adapters by concrete type.
Direction: split `ProviderCatalog` (parse) from `ProviderRouter` (select) and keep `RetryArbiter` as a pure sequencing primitive.

**[med] `adapters/web/ChatStore.java`: 269 LOC / 36 public members / public mutable nested message fields — `adapters/web/ChatStore.java:31-269`**
Evidence: `ChatMessage` with 11 `public` fields (`:53-65`); it serves as trace sink, chat log, client-wait rendezvous (`:176-248`) and attachment tracker (`:261-266`).
Why it hurts: it is simultaneously a data store, a `Condition`-like blocking primitive and a serialization format; four unrelated adapters share it (see §5).
Direction: separate `ChatLog` (append/read) from `ClientWaitCoordinator`.

**[med] `AgentRuntime` owns a queue, a wait coordinator, a tool loop, a state machine, a worker thread and an unbounded history — `runtime/AgentRuntime.java:28-241`**
Evidence: 9 fields (`:35-46`), constructs `TaskQueue`/`WaitCoordinator`/`ToolLoop` itself (`:39-40`, `:58`), and runs the loop (`:184-200`).
Why it hurts: the class is a container *and* a scheduler *and* a state machine; the three inner collaborators cannot be replaced or observed independently.
Direction: inject the queue and wait coordinator; extract a `RoleWorker` for the thread body.

---

## 2. Leaky or useless abstractions

**[high] 11 of 13 ports have exactly one implementation, and 3 are 1:1 pass-throughs over another in-process type**
Evidence: `ClockPort`→`ClockService`, `TeamPort`→`TeamRuntime`, `LlmPort`→`OpenAiCompatibleClient`, `MailPort`→`MailServiceAdapter`, `ComputerPort`→`LegacyComputerAdapter`, `ToolPort`→`ToolService`, `NoteRepository`/`TodoRepository`/`StateRepository`/`SkillRepository`→single `Json*` classes (grep `implements <Port>` yields one hit each). `MailServiceAdapter.send/inbox/open/mode` (`adapters/mail/MailServiceAdapter.java:44-75`) and every `LegacyComputerAdapter` method (`adapters/computer/LegacyComputerAdapter.java:34-115`) are bare delegations.
Why it hurts: the port indirection buys no substitutability, only an extra file and an extra mapping step; `MailPort.MailMessage` is a field-for-field copy of `MailService.MailMessage` (`ports/MailPort.java:37-49` vs `adapters/mail/MailService.java:111`, conversion at `MailServiceAdapter.java:77-80`).
Direction: keep ports only where a second implementation is real (`InputPort` has two; `LlmPort` is a true boundary). Collapse the rest.

**[high] `ComputerPort` is a 1:1 mirror of the legacy `Computer` and reconstructs an exit code by parsing a display string**
Evidence: `LegacyComputerAdapter.exec` (`:50-57`) calls `computer.runCommand` which returns a formatted human string; `parseExit` (`:117-131`) then re-parses `[exit N]` out of it to fill `ExecResult.exitCode`. `mcpTools()`/`callMcpTool()` forward `Map<String,Object>` straight through (`:84-95`).
Why it hurts: the "typed" port type `ExecResult(output, exitCode)` is fiction — the exit code is a substring of a message, and any change to the message prefix silently turns failures into successes (`FailureText.isFailure` `kernel/FailureText.java:10-13` is the only other guard).
Direction: make `ComputerPort.exec` return a real `(exitCode, stdout, stderr)` triple from the process layer; delete `[exit N]` formatting/parsing.

**[high] DTOs live in `ports/`, so "ports" is not an interface package — `ports/ChatMessage.java:13`, `ports/ToolCall.java:6`, `ports/ToolResult.java:4`, `ports/ToolSpec.java:11`**
Evidence: four data records in the package whose own README table calls it "interfaces only" (`README.md:41`).
Why it hurts: the claimed dependency rule "everything depends inward on ports" now lets `domain`-adjacent code import transport records; `ToolSpec` even wraps a kernel `JsonSchema`.
Direction: move these to `kernel`/`domain` (or a `contract` package) and leave only behaviour interfaces in `ports`.

**[high] `Map<String,Object>` is the real interface at every JSON boundary; `Payload` only renames it**
Evidence: 9 sites in `Application.java`, 14 in `ChatWebAdapter.java`, 14 in `JsonStateRepository.java`, 28 in `OpenAiCompatibleClient.java`, 21 in `MCPServer.java`. `Payload` is `record Payload(Map<String,Object> values)` (`domain/Payload.java:16`) with `asMap()` (`:38`) handed straight to `MailService`/`Computer`/`ChatStore`; `ChatWebAdapter` is constructed as `Supplier<Map<String,Object>>` (`:45,53`), and `JsonStateRepository` writes `spec.computerKwargs().asMap()` verbatim (`:117`).
Why it hurts: `Payload` claims to replace raw maps "instead of re-implementing casts everywhere" (its own javadoc `:9-15`), yet every crossing is still untyped, and each consumer re-extracts fields with a different default (`Json.str/intVal` `kernel/Json.java:118-185`, `Payload.str/intVal/boolVal` `domain/Payload.java:50-118`, plus private copies `JsonStateRepository.str/intOf/doubleOf/mapOf` `:186-224`, `Computer.mapOf` `:238`, `PodmanComputer.mapOf` `:216`).
Direction: pick one accessor library, delete the rest, and give cross-boundary payloads real record types.

**[med] Stringly-typed failure protocol on `LlmPort` while `ToolResult` uses a boolean**
Evidence: `LlmPort.API_ERROR_PREFIX = "[API error:"` (`ports/LlmPort.java:16`) with `failed()` implemented as `text.startsWith(...)` (`:41-43`, `:60-62`); tool loop branches on it (`runtime/ToolLoop.java:75`); `ToolResult` instead carries `boolean ok` (`ports/ToolResult.java:4`); `ComputerPort` uses the third convention, free text matched by `FailureText` (`kernel/FailureText.java:10`).
Why it hurts: three failure models in one call path; an LLM provider that returns a real answer beginning with "[API error:" is misclassified, and a tool that returns an error as text is not.
Direction: give `LlmPort` an explicit `Result`/`ok` field like `ToolResult`, and delete `FailureText` heuristics.

**[med] Stringly-typed identity leaks into the domain model**
Evidence: `Task.assignedRoleId()` returns `String` and the class invents a private `record RoleIdRef(String value)` to avoid importing `RoleId` — `domain/Task.java:78-85, 108-110`; `TaskQueue`/`DispatchService` then convert back. `RoleSpec.toolkits()` is `List<String>` matched by string key into `ToolkitCatalog` (`tools/spi/ToolkitCatalog.java:39-51`), and `RoleSpec.computerKind()` is a raw string switched on by `ComputerManager.create`.
Why it hurts: the "ids are not freely interchangeable Strings" goal (`kernel/RoleId.java:6-8`) is violated by the very class that holds the assignment; a typo in a toolkit name is only caught at hire time (`ToolkitCatalog:44`), and an unknown `computerKind` is only caught inside the legacy manager.
Direction: `Task` should hold `RoleId`; `toolkits` should be a typed id or resolved at load time.

**[med] `ClockService` is not the only clock API; the concrete engine is re-exported and mutated from `app`**
Evidence: `ClockService.engine()` (`:95-97`); `Application.saveState` reads `clock.engine().baseDate()` (`:429`); `Application.restoreState` writes `clock.engine().setBaseDate(...)` (`:451-452`). `ClockEngine` exposes mutators `setBaseDate` (`:65-67`) and `resetTo` (`:91-97`).
Why it hurts: `ClockPort` exists so consumers cannot touch clock state, yet the composition root holds `ClockService` concretely (`Application.java:102,606`) and mutates engine internals — so tick/day integrity is not owned by one class.
Direction: put base-date/restore on `ClockPort` (`restoreTo(day, tick, baseDate)`) and make the engine package-private.

**[low] Dead members on `ClockPort` and dead inverse-mappers in `domain`**
Evidence: `ClockPort.isWorkingHours()` (`:24`), `isShiftEnd()` (`:26`), `nextEventTick()` (`:32`) have no callers outside `ClockService` itself and `ClockEngineTest`; `Priority.from(int)` (`domain/Priority.java:26`) and `TaskStatus.fromWire(String)` (`domain/TaskStatus.java:15`) have zero callers, while `DispatchService.toTask` (`:67-72`) and `Application.toTask` (`:475-481`) re-implement them with raw switches.
Why it hurts: dead API implies capability that no caller uses, and the live copies are the ones that drift.
Direction: delete the dead members, or route the switches through them.

---

## 3. Opaque or tangled control flow

**[high] Event→behaviour round-trip: the clock publishes a domain `SHIFT_START` event so the composition root's sink lambda can call back into `LifecycleCoordinator`**
Evidence: `Application.java:208-215` installs `EventSink sink = event -> { if SHIFT_START lifecycle.onShiftStart(); else if SHIFT_END lifecycle.onShiftEnd(); dispatch.dispatch(event); }`; `onShiftStart`/`onShiftEnd` (`runtime/LifecycleCoordinator.java:93-108`) are *not* on `LifecycleGate` (`runtime/LifecycleGate.java:9-25`), which is the interface `ClockService` was given.
Why it hurts: the clock's only real dependency is "notify shift boundary"; instead the behaviour is reified as data, dispatched through a sink lambda defined 100 lines away, and then dispatched again as a task to every role (so a shift-start event also becomes a queued `Task`, `DispatchService.toTask:66-80`). Reading `ClockService` alone cannot tell you who reacts to shift changes.
Direction: give `ClockService` a `ShiftListener` (or put `onShiftStart/onShiftEnd` on `LifecycleGate`) and keep `EventSink` only for genuine task-producing events.

**[high] Three collaborators independently busy-poll, and there is no signalling anywhere except `WaitCoordinator`**
Evidence: `AgentRuntime.loop` polls `queue.peek()` every 50 ms (`:184-200`, `:188`); `ClockService.loop` sleeps 250 ms busy / `checkIntervalSeconds` idle (`:181`) and re-reads the whole team state through the gate each iteration (`:163`, `LifecycleCoordinator.anyBusy/allIdle/rolloverReady` iterate `team.all()` `:36-74`); `Application`'s `awaitIdle` polls at 20 ms (`AgentRuntime.java:170-180`).
Why it hurts: with 47 roles hired by default (`app/RoleSpecLoader.java:33-45`) that is ~940 wakeups/s purely to discover that nothing changed, and the clock samples an unsynchronized view of team state — `rolloverReady` can be observed between two roles' transitions.
Direction: `TaskQueue.push` should signal a `Condition` the worker awaits; the clock should be notified on state transitions instead of polling.

**[high] The delivery rule is expressed twice, and the dispatcher's copy is decorative**
Evidence: `DispatchService.dispatch` computes `DeliveryPolicy.forState(...)` (`:57`) but then *unconditionally* calls `runtime.get().submit(task)` (`:59`) — `DeliveryMode.DEFER_UNTIL_IDLE`/`HOLD_UNTIL_SHIFT` (`domain/DeliveryMode.java:8-10`) are never acted on anywhere (grep: no other readers). The actual hold is re-implemented in `AgentRuntime.isHeld` (`:202-208`) which compares `task.urgency()` against `Priority.EMERGENCY.value()` by hand.
Why it hurts: the domain owns a delivery policy that has no effect, and the runtime owns the real policy invisibly. Changing `DeliveryPolicy` changes nothing; changing `isHeld` is the only way to change behaviour — the opposite of what the javadoc claims (`domain/DeliveryPolicy.java:5-10`).
Direction: either enforce `mode` in `dispatch` (queue into a held lane) or delete `DeliveryMode` and let `isHeld` be the single rule.

**[high] Cyclic construction is broken with mutable one-element arrays that are read from a lambda invoked later**
Evidence: `Application.java:179-193` declares `final ToolkitCatalog[] catalogHolder` / `final ClockPort[] clockHolder`, the `TeamRuntime` factory lambda reads `catalogHolder[0]` and throws `IllegalStateException("application used before wiring completed")` if null (`:187-188`), and the holders are filled at `:217` and `:254`.
Why it hurts: the type system no longer proves the wiring is complete; the failure surfaces as a runtime exception from a worker thread on the first hire, and `clockHolder[0]` is captured by the prompt factory (`:192`) so the system prompt silently loses the clock line if the order ever changes (`systemPrompt` guards with `clock != null`, `:622`).
Direction: introduce a `Clock` accessor object or build the catalog after the clock and pass it explicitly; a holder array is a missing constructor argument.

**[med] `WaitCoordinator.await` can return "no reply" on a spurious wakeup or a concurrent `end()`**
Evidence: `await` (`:42-60`) calls `condition.await(...)` exactly once and then returns `Optional.ofNullable(reply)` — no `while (waiting && reply == null)` loop. `end()` (`:111-120`) clears `waiting`/`reply` without `signalAll()`, so `AgentRuntime.stop()` (`:88-96`) relies on `interrupt()` to unblock a waiter; `deliver()` (`:63-75`) never clears `waiting` but overwrites `reply`.
Why it hurts: `talk(wait=true)` (`runtime/TeamRuntime.waitForReply:185-203`) can report a timeout/abort even though a reply is in flight, and the correctness of shutdown depends on interrupt timing rather than a signal.
Direction: loop on the predicate inside `await`, and have `end()` signal.

**[med] `ClockService.loop` swallows every `RuntimeException` — `runtime/ClockService.java:178-180`**
Why it hurts: a `NullPointerException` in a gate or sink silently freezes time advancement for the rest of the run; the only symptom is a clock that stops.
Direction: catch, log via `TracePort.notice`, and count failures.

**[med] `Application.stop()` ordering races the snapshot against live worker threads**
Evidence: `stop()` (`:321-333`) calls `clock.stop()` then `team.stopAll()` (which only sets `running=false` + `interrupt()`, `AgentRuntime.java:88-96`, `TeamRuntime.java:96-100`) and immediately calls `saveState()` (`:329`) which reads `runtime.pendingTasks()`/`history` (`:425-426`) while a worker may still be in `runTask`'s `finally { history.add(task); }` (`AgentRuntime.java:225-231`). No thread is ever `join`ed.
Direction: `stopAll` should join with a timeout before the snapshot; or snapshot from a quiesced copy.

---

## 4. Duplication

**[high] `RoleSpec` ⇄ `Map` is implemented four times with overlapping key sets**
Evidence: `RoleSpecLoader.toSpec` (`app/RoleSpecLoader.java:146-174`, keys `group/toolkits/name/username/uid/title/responsibilities/personality/skills/system_prompt_extra/email/computer_kind/computer_kwargs`), `JsonStateRepository.roleMap` (`adapters/persistence/JsonStateRepository.java:102-123`, same 14 keys plus runtime fields), `JsonStateRepository.roleState` (`:125-141`), and `RoleSpecFactory.create` (`app/RoleSpecFactory.java:66-87`, keys `role_id/title/responsibilities/personality/skills/system_prompt_extra`).
Why it hurts: adding one `RoleSpec` field requires four coordinated edits; the loader and the state repository will drift (the repository already stores a `state`/`pending_tasks`/`history` in the same flat map, mixing definition and runtime state).
Direction: one `RoleSpecCodec` (map ⇄ record) used by both loader and repository.

**[high] `Task` ⇄ `TaskSnapshot` ⇄ `Map` is a two-hop DTO chain for the same five fields**
Evidence: `Application.toSnapshot/toTask` (`app/Application.java:464-483`) and `JsonStateRepository.taskMap/tasks` (`:144-185`); `TaskSnapshot` (`ports/StateRepository.java:39-49`) carries the same data as `Task` plus a `Payload` copy, minus `assignedRole`, and encodes status as a lowercase string that `Application.toTask` switches on (`:475-481`) instead of using `TaskStatus.fromWire`.
Why it hurts: restoring a task goes `Map → TaskSnapshot → Task` with two hand-written field lists; the persisted `status` string vocabulary is defined in `TaskStatus.wireName` and re-parsed positionally.
Direction: persist `Task` directly via one codec; drop `TaskSnapshot`.

**[med] The "is the team idle" predicate exists three times**
Evidence: `TeamRuntime.allIdle` (`runtime/TeamRuntime.java:102-112`), `LifecycleCoordinator.allIdle` (`runtime/LifecycleCoordinator.java:46-56`) — byte-identical logic — and a third variant in `AgentRuntime.awaitIdle` (`:170-180`). `LifecycleCoordinator.rolloverReady` (`:64-74`) and `TeamRuntime.allIdle` also both iterate `team.all()`.
Why it hurts: `TeamRuntime` already exposes `allIdle`, yet `LifecycleCoordinator` re-implements it against the concrete class instead of calling it — two places to fix the (already wrong) empty-team rule, which returns `false` for zero roles (`TeamRuntime.java:103-105`, `LifecycleCoordinator.java:47-49`).
Direction: `LifecycleCoordinator` should delegate to `TeamRuntime`/`TeamPort`.

**[med] Two `MailMessage` models and a re-implemented address allocator**
Evidence: `ports/MailPort.MailMessage` (`:37-49`) vs `MailService.MailMessage` with `fromDict` (`adapters/mail/MailService.java:111-170`); `MailServiceAdapter.addressFor` re-derives `username@domain` (`:32-42`) with the comment "Address allocation is reimplemented on RoleSpec" (`:12-14`), while `MailService` has its own mailbox allocation and `mailboxes.json` (`:41`).
Why it hurts: a role's address can differ between the port path and the service path; the state file and `mailboxes.json` can disagree.
Direction: keep one address authority; have the port delegate.

**[med] Four near-identical hiring methods, each re-reading and re-parsing the classpath JSON**
Evidence: `Application.hireDefaultRoles/hireAllRoles/hireRoles/hire` (`:502-535`) each call `RoleSpecLoader.fromClasspath(config.toolkits().defaults())` (full file read + parse) before looping. `HrToolkit` gets a fourth loader constructed per call (`:249-251`).
Why it hurts: `role_templates.json` is parsed once per call instead of once per application, and the four methods differ only in the selector.
Direction: load the loader once in `create()` and expose one `hire(Selection)`.

**[med] `computerLookup` is written twice, and the registry never gets cleaned**
Evidence: the identical `computerPorts.computeIfAbsent(id, key -> ComputerAdapters.open(computers, runtime.spec()))` lambda appears at `Application.java:219-221` and again at `:581-584`.
Why it hurts: two lazy-allocation sites for the same resource; neither removes the entry when `TeamRuntime.fire` is called (`TeamRuntime.java:53-60`), so `computerPorts` and `ToolService.bindings` leak for every fired/hired cycle.
Direction: one `ComputerRegistry` collaborator owned by the role lifecycle, wired into `fire`.

**[low] Three private copies of a `Map`-coercion helper plus two full accessor libraries**
Evidence: `JsonStateRepository.mapOf/str/intOf/doubleOf/strList` (`:186-224`), `Computer.mapOf` (`:238`), `PodmanComputer.mapOf` (`:216`) alongside `Json.str/intVal/doubleVal/boolVal/strList` (`kernel/Json.java:118-185`) and `Payload.str/intVal/boolVal` (`domain/Payload.java:50-118`).
Direction: delete the private copies; keep `Json` (or `Payload`) only.

---

## 5. Ownership / lifecycle ambiguity

**[high] Nobody owns shutdown of computers, MCP servers or tool bindings**
Evidence: `Application.stop()` (`:321-333`) stops the clock, the team and the web server only. `ComputerManager.destroy(roleId)` (`computers/ComputerManager.java:310`) has zero callers (grep), so podman containers are never removed; `ToolService.unbind(role)` (`tools/spi/ToolService.java:45`) has zero callers, so bindings for fired roles persist; `MCPServer` starts a process plus two threads (`adapters/mcp/MCPServer.java:78-99`) and is only destroyed inside `MCPServer` itself (`:124`).
Why it hurts: `AgentRuntime.stop()` (`:88-96`) releases nothing but a thread flag, `TeamRuntime.fire` (`:53-60`) claims to be "the resignation path" but leaves the role's computer powered on and its tools bound.
Direction: give `AgentRuntime`/`TeamRuntime` a `dispose` that powers off the computer, unbinds tools and stops MCP sessions.

**[high] Process-global singletons in adapters contradict the stated instance-scoped design**
Evidence: `ComputerManager.INSTANCE` + `getInstance()` (`computers/ComputerManager.java:49-52`) with `PodmanComputer` falling back to it when no manager was injected (`computers/PodmanComputer.java:47`); `MailService.getMailService()`/`resetInstance()` (`adapters/mail/MailService.java:475-487`); `RetryArbiter.SHARED` keyed by base URL (`adapters/llm/RetryArbiter.java:72,86`). The class javadoc claims "two `TeamRuntime`s in one JVM never share state" and "several applications can run in one JVM without sharing state" (`runtime/TeamRuntime.java:20-22`, `app/Application.java:76-80`).
Why it hurts: `ComputerAdapters.open` uses the *injected* manager, but any other construction path lands on the process singleton; `RetryArbiter` deliberately globalizes retry ordering across applications.
Direction: remove the singletons (keep the static factories only if the registry is passed explicitly).

**[high] `AgentState` is written from six places, so no class owns the state machine**
Evidence: `AgentRuntime.runTask` (`:212, 228-230`), `LifecycleCoordinator.forceWrapUp/onShiftStart` (`:83-99`), `TeamRuntime.waitForReply` (`:191-201`), `TeamRuntime.setState` (`:205-211`), `Application.restoreState` (`:442`), and two composition-root callbacks passed into tools — `TimeToolkit` forcing `IDLE` (`Application.java:231-232`) and `MemoryToolkit` forcing `OFF_DUTY` (`:233-234`).
Why it hurts: the `OFF_DUTY` gate that holds tasks (`AgentRuntime.isHeld:206`) is set by a *memory tool* and by a wrap-up timer; `runTask`'s `if (state == AgentState.BUSY) state = IDLE` (`:228-230`) silently refuses to reset `OFF_DUTY`, which is load-bearing but undocumented. `WRAPPING_UP` (`domain/AgentState.java:11-12`) is never assigned anywhere.
Direction: funnel every transition through one `AgentStateMachine` (or a `setState(from,to)` guard) and delete the tool callbacks.

**[high] Tool binding happens as a side effect of a role *factory*, keyed by a global map**
Evidence: `Application.java:185-193` — the `TeamRuntime.AgentFactory` lambda calls `tools.bind(spec.id(), catalog.forSpec(spec))` before returning the runtime; `ToolService` holds `Map<RoleId, Binding>` (`tools/spi/ToolService.java:31`).
Why it hurts: the factory is documented as "Creates an `AgentRuntime` for a spec" (`TeamRuntime.java:25-29`) but has an undeclared side effect on a shared service; `register(spec, runtime)` (`TeamRuntime.java:39-42`, used by tests and restore) bypasses it, so a restored role can end up with no tools.
Direction: bind tools where the toolkit list is resolved, or make binding part of `AgentRuntime` construction.

**[med] Persistence orchestration lives in the composition root, and its failure is swallowed**
Evidence: `Application.saveState` (`:419-430`) walks the team and builds the snapshot; `JsonStateRepository` only maps (`:87-141`); `stop()` wraps `saveState` in `catch (RuntimeException ignored)` (`:328-332`) with the comment "persistence must never prevent shutdown".
Why it hurts: a corrupt/partial write is indistinguishable from success; the "who owns state" answer is "the app object", so `restoreState` (`:433-458`) both re-hires roles (which *starts* workers, `TeamRuntime.hire:44-50`) and rewrites clock internals (`:448-452`) — a restore performs three different lifecycles in one method.
Direction: extract a `SnapshotService` that owns read/write and returns a plan the caller applies.

**[med] Hiring *is* starting: three different start paths**
Evidence: `TeamRuntime.hire` creates + registers + starts (`:44-50`); `register` does not start (`:39-42`); `startAll` starts whatever is registered (`:90-94`); `Application.create`'s factory creates without starting (`:191`). `Main` calls `hireDefaultRoles()` (`:46`) and `restoreState()` (`:50`) — both of which start workers — *before* `app.start()` (`:55`), so the documented ordering comment "Start workers first, then the clock (so `SHIFT_START` always has consumers)" (`Application.java:315`) describes only the `startAll` wrapper.
Direction: separate `add` from `start`, and have exactly one place start workers.

**[low] `WebInputAdapter` is constructed per call over a shared store**
Evidence: `Application.webInput()` returns `new WebInputAdapter(chatStore)` on every invocation (`:355-357`), while `ChatWebAdapter` and `ChatTraceAdapter` hold the same `chatStore` (`:166-167`, `:344`); `ClientToolkit` is given *both* the port (`inputRef::get`, `:243`) and a direct `Consumer` that calls `chatStore.record` (`:245-247`).
Why it hurts: there is no single owner of the client-conversation state; the port and the store are two views of the same object, and recording bypasses the port entirely.
Direction: one `ClientChannel` object created once in `create()`, exposing both the port and the recorder.

---

## 6. Test / guard smells

**[high] `ArchGuardTest` checks only 5 of 9 packages, so the documented layering is unenforced — `src/test/java/com/agent/software/architecture/ArchGuardTest.java:29-34`**
Evidence: `ALLOWED` contains `kernel`, `config`, `domain`, `ports`, `runtime` only. `adapters`, `app`, `tools`, `computers` are never inspected, yet `README.md:19-20` claims "`app → adapters → runtime → ports → domain → kernel` … The rule is enforced by `ArchGuardTest`". The concrete violation the guard cannot see: `computers/Computer.java:3` imports `com.agent.software.adapters.mcp.MCPServer` — business logic depending on an adapter.
Why it hurts: the refactor's central invariant is asserted, not verified; the guard passes on the current tree precisely because the violating package is absent from the list.
Direction: express the rule as a full package-adjacency matrix over every `com.agent.software.*` package (or use ArchUnit) so an unlisted package fails the test.

**[med] The guard inspects `import` lines only, so fully-qualified usage escapes it**
Evidence: `importsOf` (`ArchGuardTest.java:65-79`) only reads lines starting with `import `; `runtime/ClockService.java:121` already uses `List<com.agent.software.domain.ScheduleEntry>` inline, and `app/Application.java:355` uses `new com.agent.software.adapters.input.WebInputAdapter(...)` inline. Any future violation written FQN — or via a nested class, `Class.forName`, or a service loader — passes silently.
Why it hurts: the guard author's own code already uses the escape hatch, so the check is only as strong as the style of the next editor.
Direction: parse with a real AST (JavaParser/ArchUnit) or at minimum resolve FQN package prefixes in the body, not just imports.

**[med] `ALLOWED` lists a package that does not exist (`utils`), making the allow-list untrustworthy**
Evidence: `ArchGuardTest.java:31` — `"config", Set.of("kernel", "config", "utils")`; `ls src/main/java/com/agent/software` shows no `utils` directory.
Why it hurts: the allow-list is maintained by hand and clearly stale relative to the tree, which is the same failure mode that let `adapters`/`computers` go unchecked.
Direction: derive the package set from the filesystem and fail on entries with no directory.

**[high] `offDutyRecipientIsReportedAsHeld` asserts the decorative policy instead of the behaviour**
Evidence: `TeamDispatchTest`… `src/test/java/com/agent/software/runtime/TeamAndDispatchTest.java:100-109`: asserts `DeliveryMode.HOLD_UNTIL_SHIFT` **and** `queueDepth() == 1, "held tasks still queue"`.
Why it hurts: the test name says the recipient is "held", but the assertion codifies that nothing is held — it locks in the §3 duplication (dispatch reports a mode that no code consumes) and would fail if someone made `DeliveryMode` real. It is a test that protects a bug.
Direction: assert observable behaviour — a normal task must not start while off duty (that test exists at `AgentRuntimeTest.java:57-78`) — and delete the mode assertion, or make `dispatch` honour the mode.

**[med] The runtime tests are timing-dependent by construction**
Evidence: `AgentRuntimeTest.offDutyHoldsNormalTaskUntilStateChanges` uses `Thread.sleep(150)` and then asserts the task has not started (`:67-68`); `awaitIdle` polls at 20 ms (`AgentRuntime.java:177`); `FakeLlm` throws `IllegalStateException("no scripted reply left")` (`RuntimeFakes.java:48-49`) if the worker polls after the script is exhausted.
Why it hurts: with 47-role default teams and a 20 ms poll, these tests encode "150 ms is enough"; on a loaded CI they flake, and a real failure (task started early) is indistinguishable from a slow scheduler.
Direction: replace polling with a latch-based test seam (the worker should signal on state change — see §3).

**[med] `Application.create`'s cycle-breaking holders make the composition root untestable except end-to-end**
Evidence: `ApplicationEndToEndTest` is the only test for the app package besides `RoleSpecFactoryTest`/`RoleSpecLoaderTest`; there is no test that constructs `Application` without a real `ChatStore` + `MailService` + `JsonStateRepository` (all created unconditionally in `create`, `:166-176`), no test that calls `restoreState` with a malformed snapshot, and no test for `stop()` (the un-joined threads and the swallowed persistence error at `:328-332`).
Why it hurts: the exact areas with the highest defect density — shutdown ordering, restore semantics, the holder-array wiring — have no unit-level guard.
Direction: split `create` into a `Dependencies` value object so tests can inject fakes, then cover shutdown/restore.

**[low] `ArchGuardTest` also asserts nothing about `Map<String,Object>` boundaries or `ports` purity**
Evidence: the guard is purely import-direction (`:36-57`); `ports/ChatMessage.java:13` (a record in `ports`) and `Payload.asMap()` crossing into `MailService`/`Computer` are invisible to it.
Direction: add a rule that `ports` contains no records/classes other than interface-nested types, and a budget rule on `Map<String,Object>` declarations per package.

---

## Ranked top 10 most damaging problems

1. **`ArchGuardTest` guards 5 of 9 packages and only `import` lines, so the layering it "enforces" is already violated** (`ArchGuardTest.java:29-34`, `:65-79`; `computers/Computer.java:3` is the live violation). Every other structural claim in this branch rests on a check that cannot fail for the packages that actually break the rule.
2. **The delivery policy is decorative: `DispatchService` computes a mode and then always submits** (`DispatchService.java:57-59`), while the real gate is a second, hidden copy in `AgentRuntime.isHeld` (`:202-208`). The domain's central scheduling rule is not the rule in force.
3. **`Application` is a 642-line, 35-public-member, 18-field class that is simultaneously composition root, persistence service, HTTP DTO mapper and prompt builder** (`Application.java:82-642`), with cycle-breaking via mutable one-element arrays (`:182-183, 187-188, 217, 254`).
4. **Nothing owns teardown: containers, MCP processes and tool bindings are never released** — `ComputerManager.destroy` (`:310`) and `ToolService.unbind` (`:45`) have zero callers; `Application.stop()` (`:321-333`) stops only clock/team/web, and `stopAll` never joins the threads it interrupts (`AgentRuntime.java:88-96`).
5. **`Map<String,Object>` is the true cross-boundary type; `Payload` is a rename with three competing accessor libraries** (`domain/Payload.java:16,38`; `kernel/Json.java:118-185`; `JsonStateRepository.java:186-224`; 28 sites in `OpenAiCompatibleClient`, 21 in `MCPServer`).
6. **`RoleSpec` and `Task` are each mapped record↔Map in four and two places respectively** (`RoleSpecLoader.java:146-174`, `JsonStateRepository.java:102-141`, `RoleSpecFactory.java:66-87`; `Application.java:464-483` + `JsonStateRepository.java:144-185`), guaranteeing drift between the loader, the factory and the snapshot.
7. **Control flow is inverted through data: the clock publishes a `SHIFT_START` `Event` so a lambda in the composition root can call `LifecycleCoordinator.onShiftStart`, a method absent from the `LifecycleGate` the clock was given** (`Application.java:208-215`, `LifecycleGate.java:9-25`, `LifecycleCoordinator.java:93-108`) — and that same event is then dispatched as a queued `Task`.
8. **`computer` + `Mail` port adapters are pure pass-throughs over retained legacy classes, and `ComputerPort.exec`'s typed `exitCode` is re-parsed out of a display string** (`LegacyComputerAdapter.java:34-131`, `MailServiceAdapter.java:44-80`), so "adapters depend on ports" is nominal: ports depend on the legacy shapes.
9. **Process-global singletons and SHARED registries undercut the stated multi-instance isolation** (`ComputerManager.java:49-52`, `PodmanComputer.java:47`, `MailService.java:475-487`, `RetryArbiter.java:72,86`) while `README.md`/class javadoc promise instance-scoped state.
10. **`AgentState` has no owner: six writers, two of them callbacks injected into tools, and `WRAPPING_UP` is never assigned** (`Application.java:231-234`; `LifecycleCoordinator.java:83-99`; `TeamRuntime.java:191-201`; `AgentRuntime.java:212,228-230`), so the off-duty gate that controls whether work runs is set from a memory tool and a wrap-up timer.

---

### Secondary but concrete

- **Dead configuration**: `AppConfig.Computer` is parsed (`ConfigLoader.java:93-96`) and never consumed; `Application.buildMailService` ignores `cfg.mail().dataDir()` and re-derives it (`:286`). The real values are the static constants `Computer.DEFAULT_IMAGE`/`DRIVE_ROOT`/`ComputerManager.DEFAULT_NETWORK_NAME`.
- **`ToolLoop` never enforces the token budget when the model answers without tool calls** (`ToolLoop.java:79-80` returns before the check at `:91-94`), re-sends all tool specs every round (`:71`), and hardcodes temperature `0.7` outside `Policy` (`:71`).
- **`ClockService.nextEventTick()` on the port recomputes gate state differently from the internal fast-forward path** (`:79-81` vs `:201`), so the port's answer and the engine's actual jump can disagree.
- **`WaitCoordinator` correctness depends on interruption** (`end()` does not signal, `:111-120`) and `await` has no predicate loop (`:42-60`).
- **`Task.history` is unbounded** (`AgentRuntime.java:41,226`) and `ChatStore.MAX_HISTORY = 5000` is the only bounded buffer (`ChatStore.java:34`).
