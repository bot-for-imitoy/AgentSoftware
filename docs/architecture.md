# Architecture

## Layers

```
┌────────────────────────────────────────────────────────────┐
│ app          Application · RoleSpecLoader · RoleSpecFactory │  composition root
│              · Main                                         │
├────────────────────────────────────────────────────────────┤
│ adapters     HTTP LLM + provider catalog + RetryArbiter ·    │
│              computers (podman/ssh/local) · stdio MCP ·      │
│              state/note/todo/skill repositories · mail ·     │
│              ChatStore/ChatWebAdapter · console/Web input    │
├────────────────────────────────────────────────────────────┤
│ runtime      AgentRuntime · TaskQueue · ToolLoop ·           │
│              WaitCoordinator · TeamRuntime · ClockEngine/    │
│              ClockService · DispatchService ·                │
│              LifecycleCoordinator                            │
├────────────────────────────────────────────────────────────┤
│ ports        LlmPort · ToolPort · ComputerPort · MailPort ·  │
│              InputPort · TracePort · ClockPort · EventSink · │
│              TeamPort · State/Note/Todo/SkillRepository      │
├────────────────────────────────────────────────────────────┤
│ domain       Event · Task · RoleSpec · AgentState ·          │
│              Payload · ShiftCalendar · DeliveryPolicy        │
├────────────────────────────────────────────────────────────┤
│ kernel       ids · Json · JsonSchema · Names · FailureText · │
│              AgentException                                  │
└────────────────────────────────────────────────────────────┘
tools (spi + builtin) depends on ports + domain only.
computers is the backend used by the computer adapter.
```

Dependency direction is strictly inward: `app → adapters → runtime → ports →
domain → kernel`. `ArchGuardTest` scans imports and fails the build if a new
layer reaches into a disallowed package.

## Rules that the codebase enforces

- **No process-wide singletons.** There is no `getInstance()`/`getDefault*` in
  the runtime; each `Application` owns its collaborators.
- **Ports are interfaces only.** Concrete classes live in `adapters` (or
  `computers`); `runtime` never imports `adapters`.
- **Domain is pure.** No threads, no file IO, no environment access.
- **Events carry recipients.** An empty recipient set means broadcast;
  `DeliveryPolicy` decides immediate vs held based on lifecycle state only.
- **Tools are data-driven.** A role's toolkits come from `RoleSpec.toolkits`,
  loaded from `role_templates.json`.

## Call order

### Startup

```
ConfigLoader.load → AppConfig → AppPaths
adapters: ProviderEndpointResolver → OpenAiCompatibleClient(RetryArbiter)
          MailService · Json*Repository · JsonStateRepository · ComputerManager
tools:    ToolService ← ToolkitCatalog(note/todo/time/memory/pc/mcp/skill/
          email/talk/task_view/client/hr)
runtime:  TeamRuntime(AgentRuntime factory) → DispatchService →
          LifecycleCoordinator → ClockService(EventSink)
app:      Application → startWeb() (optional) → hire roles → restoreState()
start:    TeamRuntime.startAll()  (workers first)
          ClockService.start()    (clock second)
```

### Every tick

```
clock.advance()  → busy rate, or fast-forward when allIdle
SHIFT_START / SHIFT_END / TASK_DUE → EventSink → DispatchService
DispatchService: resolve recipients → DeliveryPolicy → AgentRuntime.submit
AgentRuntime worker: pop → ToolLoop (LlmPort/ToolPort) → TracePort → history
```

### Every task

```
TaskQueue.pop → Task.markRunning
ToolLoop.run: system prompt + task → chatWithTools
              tool calls executed sequentially, results fed back (role=tool)
              policy enforces maxRounds / maxTokens
trace.answer(DONE|FAILED) → history
```

### Shutdown

```
clock.stop → team.stopAll → web.stop → saveState()
```

## Where state lives

| Data | Location |
|---|---|
| snapshot (clock, role specs, tasks) | `<data>/state.json` |
| notes / summaries | `<data>/notes/<role>/…` |
| todos | `<data>/todos/<role>.json` |
| skills | `<data>/skills/…/SKILL.md` |
| mailboxes | `<data>/mail/mailboxes.json` |
| computer files | `data/computers/<role_id>` mounted at `/home/<username>` |
| chat/trace feed | in memory (`ChatStore`), served by `/api/v1/messages` |
