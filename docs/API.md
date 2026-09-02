# Shift & Event-Driven Agent Scheduler Interface Documentation
# Shift & Event-Driven Agent Scheduler Interface Documentation

**A multi-role, event-driven, Tick-based Agent scheduling framework** (Shift & Event-Driven Agent Scheduler).
This document covers the interfaces, purposes, parameters, and usage examples of all core classes and utility classes.
**A multi-role, event-driven, Tick-based Agent scheduling framework** (Shift & Event-Driven Agent Scheduler).
This document covers the interfaces, purposes, parameters, and usage examples of all core classes and utility classes.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Core Types types.py](#core-types)
3. [Time System time_manager.py](#time-system)
4. [Storage NoteStore](#storage)
4. [Storage NoteStore](#storage)
5. [Role System roles.py](#role-system)
6. [Event System EventBus / EventDispatcher](#event-system)
7. [System Management AgentSystem](#system-management)
8. [Role Templates and Factory](#role-templates-and-factory)
9. [Tool System tools.py](#tool-system)
10. [Python Toolkits python_tools/](#python-toolkits)
11. [MCP Loader mcp_toolkit.py](#mcp-loader)
12. [LLM Client llm.py](#llm-client)
13. [Complete Example](#complete-example)
12. [LLM Client llm.py](#llm-client)
13. [Complete Example](#complete-example)

---

## Architecture Overview

```
┌─────────────────────────────────────────────┐
│ AgentSystem (unified entry)                 │
│  ├── TimeEventBus   (dedicated thread, Tick) │
│  ├── TimeEventBus   (dedicated thread, Tick) │
│  ├── RolePool       (thread per role)       │
│  └── EventDispatcher (broadcast/targeted)   │
└──────────────┬──────────────────────────────┘
               ▼
  ┌─────────────────────────┐
  │ Event (target_role?)    │
  └────────────┬────────────┘
               ▼
  Each role's AgentRole: Layer 1 state mask → Layer 2 salience → enqueue as Task
               ▼
  Role worker thread: LLM + tool loop (ToolRegistry)
               ▼
  ToolKit collection: talk / hr / memory / time / task / client / MCP groups
```

---

## Core Types

File: `src/core/types.py`

### `Priority(IntEnum)`
Event priority; higher values are more urgent.

| Member | Value | Purpose |
|------|-----|------|
| `LOW` | 1 | Chit-chat, irrelevant information |
| `NORMAL` | 3 | Routine tasks, shift events |
| `HIGH` | 6 | Work tickets |
| `EMERGENCY` | 10 | Emergency events (bypass all filters) |

### `AgentState(str, Enum)`
Role lifecycle states.

| Member | Meaning |
|------|------|
| `OFF_DUTY` | Off duty. Non-EMERGENCY events are blocked by Layer 1 |
| `ON_DUTY_IDLE` | On duty, idle (default) |
| `ON_DUTY_BUSY` | On duty, busy (executing a task) |
| `WRAPPING_UP` | Wrapping up (reserved) |

### `Event` (dataclass)
```python
Event(
    id: str = auto-generated,    # event ID
    source: str = "",            # source: github/slack/time/task...
    event_type: str = "",        # new_pr/SHIFT_START/TASK_DUE...
    priority: Priority = NORMAL,
    payload: dict = {},          # payload
    timestamp: datetime = now,
    target_role: Optional[str] = None,  # targeted delivery: only sent to that role; None=broadcast
    trigger_tick: Optional[int] = None, # absolute tick to trigger (None=immediate, set by TimeEventBus)
    trigger_tick: Optional[int] = None, # absolute tick to trigger (None=immediate, set by TimeEventBus)
)
```

---

## Time System

File: `src/core/time_manager.py`

### `ScheduledTask` (dataclass)
A scheduled task, registered with TimeEventBus.
A scheduled task, registered with TimeEventBus.

| Field | Description |
|------|------|
| `description` | Task content |
| `owner_role` | Owning role (reminders are only delivered to it) |
| `target_tick` | Target tick, range **0~60** |
| `day` | Day on which to trigger (default: the current day) |
| `task_id` | Auto-generated |
| `fired` | Whether it has fired |

Method: `absolute_fire_tick(ticks_per_day)` → `(day-1)*144 + target_tick`

### `TimeEventBus`
Shift time manager (= an event bus subclass, `EventBus` + time thread), **owns a dedicated background thread**.
(The legacy `TimeManager` compatibility alias was removed in 2026-08; use `TimeEventBus` uniformly.)
### `TimeEventBus`
Shift time manager (= an event bus subclass, `EventBus` + time thread), **owns a dedicated background thread**.
(The legacy `TimeManager` compatibility alias was removed in 2026-08; use `TimeEventBus` uniformly.)

**Constants**: `MINUTES_PER_TICK=10`, `TICKS_PER_DAY=144`, `SHIFT_START_TICK=0`, `SHIFT_END_TICK=60`, `EVENT_SHIFT_START/SHIFT_END/TASK_DUE`, `TASK_TICK_MIN/MAX=0/60`

**Constructor**:
```python
TimeEventBus(minutes_per_tick=10, shift_start_tick=0, shift_end_tick=60,
             ticks_per_day=144, check_interval=30)
TimeEventBus(minutes_per_tick=10, shift_start_tick=0, shift_end_tick=60,
             ticks_per_day=144, check_interval=30)
```

**Configuration**:
| Method | Parameter | Purpose |
|------|------|------|
| `set_event_sender(fn)` | `fn(Event)` | Set the event-sending callback (wires into the event bus) |
| `set_clock(fn)` | `fn() -> datetime` | Inject a time source (default `datetime.now`; simulated clock for tests) |

**Time queries**:
| Method | Returns | Description |
|------|------|------|
| `current_tick()` | int | Cumulative ticks since system start (start=0) |
| `day_number()` | int | Day number (start day=1) |
| `tick_of_day()` | int | Tick position within today, 0~143 |
| `tick_to_time(tick)` | str | Tick → relative clock "HH:MM" |
| `is_working_hours()` | bool | Whether today is within working hours |
| `ticks_until_shift_end()` | int | How many ticks until shift end |
| `describe()` | str | "Day X, Tick Y (on duty / off duty)" |
| `get_shift_event(tick_of_day)` | str\|None | SHIFT_START / SHIFT_END / None |

**Scheduled tasks**:
| Method | Parameter | Returns |
|------|------|------|
| `schedule_task(description, owner_role, target_tick, day=None, payload=None)` | — | `ScheduledTask`; raises `ValueError` if the tick is out of range |
| `list_tasks(owner_role=None)` | In trigger order | `list[ScheduledTask]` |
| `edit_task(task_id, description=None, target_tick=None, day=None)` | — | `Optional[ScheduledTask]` |
| `cancel_task(task_id)` | — | bool |

**Lifecycle**: `start()` starts the thread (Tick 0 / Day 1), `stop()` stops it, and `is_running` is a property.
Tick is an explicit state: it does not advance with real time; it only jumps forward when all roles are idle (configurable via `set_fast_forward(enabled, idle_seconds)`).
**Lifecycle**: `start()` starts the thread (Tick 0 / Day 1), `stop()` stops it, and `is_running` is a property.
Tick is an explicit state: it does not advance with real time; it only jumps forward when all roles are idle (configurable via `set_fast_forward(enabled, idle_seconds)`).

**Automatic events**: Tick 0 of each day → `SHIFT_START` (EMERGENCY); each day at Tick >=60 → `SHIFT_END` (EMERGENCY; the instruction prompts writing a summary); due tasks → `TASK_DUE` (NORMAL, `target_role=owner`).

```python
from src.core.time_manager import TimeEventBus
tm = TimeEventBus(check_interval=1)
from src.core.time_manager import TimeEventBus
tm = TimeEventBus(check_interval=1)
tm.set_event_sender(lambda ev: dispatcher.trigger(ev))  # wire into the bus
tm.start()
print(tm.describe())          # Day 1, Tick 0 (on duty...)
task = tm.schedule_task("Write weekly report", owner_role="CEO", target_tick=45)
task = tm.schedule_task("Write weekly report", owner_role="CEO", target_tick=45)
tm.cancel_task(task.task_id)
tm.stop()
```

---

## Storage

### `NoteStore` — notes (with reminders unified with scheduled tasks) and summaries
File: `src/core/note_store.py`. Each role has its own directory (when the role has a personal computer, in the computer's working directory
`<workdir>/notes/`; otherwise locally at `data/notes/<role_id>/`).

**Notes and scheduled tasks are unified**: a note = content + optional reminder time. After `write_note` fills in
`remind_tick`, the system sends the role a reminder event (TASK_DUE) like a task when the time arrives.
### `NoteStore` — notes (with reminders unified with scheduled tasks) and summaries
File: `src/core/note_store.py`. Each role has its own directory (when the role has a personal computer, in the computer's working directory
`<workdir>/notes/`; otherwise locally at `data/notes/<role_id>/`).

**Notes and scheduled tasks are unified**: a note = content + optional reminder time. After `write_note` fills in
`remind_tick`, the system sends the role a reminder event (TASK_DUE) like a task when the time arrives.

```python
NoteStore(base_dir="./data/notes", role_id="", computer=None, time_manager=None)
NoteStore(base_dir="./data/notes", role_id="", computer=None, time_manager=None)
```

| Method | Parameter | Returns | Description |
|------|------|------|------|
| `write_note(title, content, remind_tick=None, remind_day=None)` | str,str,int\|None,int\|None | str(path) | Write a note (overwrites if it exists; if `remind_tick` is set, registers a reminder that fires an event when due) |
| `edit_note(title, content, remind_tick=None, remind_day=None)` | str,str,int\|None,int\|None | str(path) | Edit a note (if `remind_tick` is provided, resets the reminder; otherwise keeps the original one) |
| `write_note(title, content, remind_tick=None, remind_day=None)` | str,str,int\|None,int\|None | str(path) | Write a note (overwrites if it exists; if `remind_tick` is set, registers a reminder that fires an event when due) |
| `edit_note(title, content, remind_tick=None, remind_day=None)` | str,str,int\|None,int\|None | str(path) | Edit a note (if `remind_tick` is provided, resets the reminder; otherwise keeps the original one) |
| `list_notes()` | — | list[str] | List of note titles (excluding summaries) |
| `read_note(title)` | str | Optional[str] | Read a note; returns None if it does not exist |
| `delete_note(title)` | str | bool | Delete a note (actually deletes the file + cancels the associated reminder) |
| `get_reminder(title)` | str | Optional[dict] | Query the note's reminder `{"day", "tick"}`; returns None if there is no reminder |
| `delete_note(title)` | str | bool | Delete a note (actually deletes the file + cancels the associated reminder) |
| `get_reminder(title)` | str | Optional[dict] | Query the note's reminder `{"day", "tick"}`; returns None if there is no reminder |
| `save_summary(content, day=None)` | str,int\|None | str(path) | Save the Day N summary `_summary_day_N.md` |
| `get_summary(day=None)` | int\|None | Optional[str] | Read the summary for the given day |
| `get_latest_summary(before_day=None)` | int\|None | Optional[str] | The most recent summary (strictly before before_day) |

---

## Company Mail

File: `src/core/mail_service.py` (core) + `src/python_tools/email_toolkit.py` (LLM tool).

**Mail address**: each member has one mailbox `username@<suffix>`, where the suffix is defined by the environment variable `MAIL_SUFFIX` (default `company.com`); a role's explicit `email` field takes precedence. `AgentRole.mail_address` and the tool follow the same allocation rule.

**Delivery mode**: by default a **virtual implementation** (delivered to the internal mailbox and persisted in `data/mail/mailboxes.json`); once `SMTP_HOST` is configured, it automatically switches to **real smtplib sending**, while keeping an internal copy in the recipient's mailbox (marked `via_smtp`).

```python
MailService(config=MailConfig.from_env())
```

| Method | Parameter | Returns | Description |
|------|------|------|------|
| `email_for(role)` | AgentRole | str | Role → mail address (`username@suffix`; explicit email takes precedence) |
| `send(sender_email, sender_name, to, subject, body, cc=None)` | str,str,list[str],str,str,list[str]\|None | str(summary) | Send mail (virtual delivery / real SMTP sending + internal copy) |
| `inbox(email, limit=None)` | str,int\|None | list[MailMessage] | Inbox (newest first) |
| `unread_count(email)` | str | int | Number of unread mails |
| `read(email, message_id)` | str,str | Optional[MailMessage] | Open a mail (marks it read) |
| `describe()` | — | str | Description of the delivery mode (virtual / SMTP) |

**LLM tool** (auto-attached to every role, `email` toolkit): `send_email(to, subject, body, cc?)` / `read_mail(limit?, unread_only?)` / `open_mail(message_id)` / `mail_address_book(group?)`.
---

## Company Mail

File: `src/core/mail_service.py` (core) + `src/python_tools/email_toolkit.py` (LLM tool).

**Mail address**: each member has one mailbox `username@<suffix>`, where the suffix is defined by the environment variable `MAIL_SUFFIX` (default `company.com`); a role's explicit `email` field takes precedence. `AgentRole.mail_address` and the tool follow the same allocation rule.

**Delivery mode**: by default a **virtual implementation** (delivered to the internal mailbox and persisted in `data/mail/mailboxes.json`); once `SMTP_HOST` is configured, it automatically switches to **real smtplib sending**, while keeping an internal copy in the recipient's mailbox (marked `via_smtp`).

```python
MailService(config=MailConfig.from_env())
```

| Method | Parameter | Returns | Description |
|------|------|------|------|
| `email_for(role)` | AgentRole | str | Role → mail address (`username@suffix`; explicit email takes precedence) |
| `send(sender_email, sender_name, to, subject, body, cc=None)` | str,str,list[str],str,str,list[str]\|None | str(summary) | Send mail (virtual delivery / real SMTP sending + internal copy) |
| `inbox(email, limit=None)` | str,int\|None | list[MailMessage] | Inbox (newest first) |
| `unread_count(email)` | str | int | Number of unread mails |
| `read(email, message_id)` | str,str | Optional[MailMessage] | Open a mail (marks it read) |
| `describe()` | — | str | Description of the delivery mode (virtual / SMTP) |

**LLM tool** (auto-attached to every role, `email` toolkit): `send_email(to, subject, body, cc?)` / `read_mail(limit?, unread_only?)` / `open_mail(message_id)` / `mail_address_book(group?)`.

---

## Role System

File: `src/core/roles.py`

### `Urgency(IntEnum)`
`LOW=1 / NORMAL=3 / HIGH=6 / CRITICAL=8` (task queues are sorted by urgency).

### `Task` (dataclass)
`Task(description, urgency=Urgency.NORMAL, source="", payload={})`
Fields: `task_id` (auto), `status` (pending/running/done/failed), `result`, `tokens_consumed`, `assigned_role`.

### `AgentRole` (dataclass)
Role definition + task queue + LLM binding.

**Role attributes**:
| Field | Description |
|------|------|
| `name` | Person name (Zhang San / Li Si...) |
| `role_id` | Functional role (coder/reviewer/ceo...) |
| `title` | Job title |
| `responsibilities` | Responsibilities description |
| `personality` | Personality |
| `skills` | Skills list |
| `is_default` | Whether it is a default role |
| `group` | Owning group (e.g. Frontend Development Group / Leadership Group; empty = ungrouped, talk is not restricted by group) |
| `email` | Explicit company mail (optional; default `username@<MAIL_SUFFIX>`, see `mail_address`) |
| `group` | Owning group (e.g. Frontend Development Group / Leadership Group; empty = ungrouped, talk is not restricted by group) |
| `email` | Explicit company mail (optional; default `username@<MAIL_SUFFIX>`, see `mail_address`) |
| `state` | AgentState (default ON_DUTY_IDLE) |
| `interest_keywords` | Event-filtering keywords |

**Event filtering**: `evaluate_event(event) -> (bool, reason)` three-layer filtering; `event_to_task(event) -> Task`.
**Prompt**: `build_system_prompt() -> str` (automatically injects "Today is Day X" + yesterday's summary).
**Queue**: `add_task(task)` / `pop_task()` / `peek_next_urgency()` / `queue_depth` / `current_task` / `is_busy`.
**Storage and time**: `note_store` property (lazy NoteStore), `get_latest_summary(before_day=None)`, `time_manager` property, `bind_time_manager(tm)`.
**Activity journal**: `journal(entry)` — appends a line to `data/journals/<role_id>.md` (automatically written on role context updates: receiving tasks/executing/tool calls/notes/messages).
**Activity journal**: `journal(entry)` — appends a line to `data/journals/<role_id>.md` (automatically written on role context updates: receiving tasks/executing/tool calls/notes/messages).
**Tools**: `add_mcp_tool(name, description, input_schema, handler)`, `add_toolkit(toolkit) -> int`, `mcp_tool_names`.
**Communication**: `talk_to(target, message, urgency="NORMAL") -> str` (programmatic cross-role message); the LLM communicates via the talk tool (target/message/urgency/wait/attachment), and **target uses the member's person name** (the roster does not expose role_id; it is mapped internally automatically); **talk is limited to members of the same group** (cross-group talk is rejected with a hint to use mail instead); when `wait=true`, the sender enters the WAIT state and synchronously waits for the other party's reply (the message carries a "the questioner is waiting" hint; waiting has no time limit, and mutual-wait deadlocks are automatically detected and rejected); `attachment` is a company cloud-drive file path (under /mnt/drive; existence and readability are validated before sending, and the other party can read it directly).
**Mail**: `mail_address -> str` (company mail, `username@<MAIL_SUFFIX>`); the LLM sends/receives mail via the email toolkit (send_email/read_mail/open_mail/mail_address_book), and **cross-group communication uses mail**; virtual mailbox by default, real sending once SMTP is configured (see `MailService`).
**Communication**: `talk_to(target, message, urgency="NORMAL") -> str` (programmatic cross-role message); the LLM communicates via the talk tool (target/message/urgency/wait/attachment), and **target uses the member's person name** (the roster does not expose role_id; it is mapped internally automatically); **talk is limited to members of the same group** (cross-group talk is rejected with a hint to use mail instead); when `wait=true`, the sender enters the WAIT state and synchronously waits for the other party's reply (the message carries a "the questioner is waiting" hint; waiting has no time limit, and mutual-wait deadlocks are automatically detected and rejected); `attachment` is a company cloud-drive file path (under /mnt/drive; existence and readability are validated before sending, and the other party can read it directly).
**Mail**: `mail_address -> str` (company mail, `username@<MAIL_SUFFIX>`); the LLM sends/receives mail via the email toolkit (send_email/read_mail/open_mail/mail_address_book), and **cross-group communication uses mail**; virtual mailbox by default, real sending once SMTP is configured (see `MailService`).

### `RolePool`
Multi-role concurrent management; **each role has its own thread + lock + LLM client** (unified OpenAI-compatible interface provided by `OpenAICompatLLM`).

```python
RolePool(llm_api_key=None, llm_model=None)  # configuration: explicit args > -D system properties > environment variables (OPENAI_*) > ConfigStore
```

| Method | Parameter | Returns | Description |
|------|------|------|------|
| `add_role(role)` | AgentRole | None | Register (must be before start) |
| `get_role(name)` | str | AgentRole | Raises KeyError if absent |
| `all_roles()` | — | list[AgentRole] | All roles |
| `list_roles()` | — | list[str] | role_id list |
| `start()` | — | None | Start all worker threads + auto-register the talk tool |
| `shutdown(wait=True)` | bool | None | Stop |
| `assign_task(role_name, task)` | str, Task | None | Deliver a task |
| `journal_all(entry)` | str | None | Global notification; writes to every role's activity journal |
| `journal_all(entry)` | str | None | Global notification; writes to every role's activity journal |
| `get_status()` | — | dict | {role_id: {busy, queue_depth, current_task, next_urgency}} |

```python
from src.core.roles import AgentRole, RolePool, Task, Urgency
pool = RolePool()
coder = AgentRole(name="Zhang San", role_id="coder", title="Backend Engineer",
                  personality="rigorous", skills=["Python"])
pool.add_role(coder)
pool.start()
pool.assign_task("coder", Task(description="Fix login bug", urgency=Urgency.HIGH))
print(pool.get_status())
pool.shutdown()
```

---

## Event System

### `EventBus` — scheduled-event dispatch table
### `EventBus` — scheduled-event dispatch table
File: `src/core/event_bus.py`

Only handles the dispatch-table responsibilities of "registering / canceling / retrieving due scheduled events"; **does not include the filtering pipeline**
(the 3-layer filtering is each role's independent `AgentRole.evaluate_event`; see the Role System section).

Only handles the dispatch-table responsibilities of "registering / canceling / retrieving due scheduled events"; **does not include the filtering pipeline**
(the 3-layer filtering is each role's independent `AgentRole.evaluate_event`; see the Role System section).

```python
EventBus()   # TimeEventBus is its subclass (time_manager.py), providing the time thread
EventBus()   # TimeEventBus is its subclass (time_manager.py), providing the time thread
```

| Method | Parameter | Returns | Description |
|------|------|------|------|
| `register_event(event, tick)` | Event, int | str(event ID) | Register a scheduled event into the dispatch table (tick must be explicit; use TimeEventBus for immediate triggering) |
| `cancel_event(event_id)` | str | bool | Cancel a scheduled event |
| `list_scheduled_events()` | — | list[dict] | Pending events (sorted by tick) |
| `_check_due_events(current_tick)` | int | list[Event] | Retrieve due events (called by the time thread) |
| Method | Parameter | Returns | Description |
|------|------|------|------|
| `register_event(event, tick)` | Event, int | str(event ID) | Register a scheduled event into the dispatch table (tick must be explicit; use TimeEventBus for immediate triggering) |
| `cancel_event(event_id)` | str | bool | Cancel a scheduled event |
| `list_scheduled_events()` | — | list[dict] | Pending events (sorted by tick) |
| `_check_due_events(current_tick)` | int | list[Event] | Retrieve due events (called by the time thread) |

### `EventDispatcher` — multi-role broadcast/targeted dispatch
File: `src/core/dispatcher.py`

```python
EventDispatcher(pool: RolePool)
```

`trigger(event) -> dict[role_id, {"accepted", "reason", "task_id"}]`
- **Broadcast** (`target_role=None`): every role runs its own 3-layer filtering
- **Targeted** (`target_role=xxx`): delivered only to the target role (accepted directly); the others are skipped

```python
dispatcher = EventDispatcher(pool)
results = dispatcher.trigger(Event(source="github", event_type="new_pr",
                                   priority=Priority.HIGH, payload={}))
# {"coder": {"accepted": True, "task_id": "..."}, ...}
```

---

## System Management

File: `src/core/agent_system.py` — **recommended unified entry point**

```python
AgentSystem(roles=None, role_ids=None, check_interval=30, auto_toolkits=True)
```
- `roles`: a list of pre-built AgentRole objects; `role_ids`: a list of template ids
- `auto_toolkits=True`: auto-register memory/time/task tools + bind a shared TimeEventBus
- `auto_toolkits=True`: auto-register memory/time/task tools + bind a shared TimeEventBus

| Method/Property | Description |
|-----------|------|
| `add_role(role)` | Register a role (bind shared time source + auto tools) |
| `add_default_roles()` | Register the 4 default management roles and return the list |
| `get_role(role_id)` | Get a role |
| `get_status()` | Status snapshot |
| `trigger(event)` | Deliver an event (broadcast/targeted) |
| `assign_task(role_id, task)` | Directly assign a task |
| `start()` | Start the role pool + time thread (Tick 0 / Day 1) |
| `stop()` | Stop everything |
| `tick` / `day` | Current tick / day number |
| `describe()` | Shift description |
| `pool` / `time_manager` / `dispatcher` | Access to underlying components |

```python
from src.core.agent_system import AgentSystem
system = AgentSystem(role_ids=["ceo", "coo", "hr", "cfo"], check_interval=1)
system.start()
print(system.describe())   # Day 1, Tick 0
system.trigger(Event(...))
system.stop()
```

---

## Role Templates and Factory

File: `src/core/role_templates.py`

- `TEMPLATES`: 12 templates (4 management + 8 business)
- `DEFAULT_ROLES = ["CEO", "COO", "HR"]` (the CFO template is kept but not enabled by default for now; it will be added later)
- `get_template(name) -> AgentRole`: clone a template
- `create_all_roles() -> list[AgentRole]`: all 12
- `create_default_roles() -> list[AgentRole]`: default roles (currently CEO/COO/HR; CFO to be added later)

File: `src/core/role_factory.py`

```python
RoleFactory(llm=None)
factory.create_role(requirement: str) -> AgentRole
# hiring requirement → LLM generates the role configuration (role_id/title/responsibilities/...) → create the role
```
`create_role` allocates an unused person name from the name pool and registers the role in the role-template pool.

---

## Tool System

File: `src/core/tools.py`

### `ToolDef` (dataclass)
`{name, description, input_schema, handler, source("python"/"mcp:<package name>"), mcp_tool}`

### `ToolKit` — a toolkit (a group of related tools)
```python
ToolKit(name, description="")
tk.add_python_tool(name, description, input_schema, handler) -> ToolDef
tk.tool_names / tk.tool_count / tk.get_tool(name) / __iter__ / __contains__
```
Built-in: `create_coding_toolkit()` (read_file/edit_file/run_cmd), `create_web_toolkit()` (http_get/http_post).

### `ToolRegistry` — role-level tool registry
| Method | Parameter | Returns |
|------|------|------|
| `add_toolkit(toolkit)` | ToolKit | int (number added; duplicate names skipped) |
| `remove_toolkit(name)` | str | int |
| `add_tool(...)` / `remove_tool(name)` | — | — |
| `list_tools()` | — | list[dict] (name/description/input_schema) |
| `call_tool(name, arguments)` | str, dict | CallToolResult |
| `get_tools_prompt()` | — | str (LLM-readable tool descriptions) |
| `tool_names` / `toolkit_names` / `tool_count` | — | — |

---

## Python Toolkits

Directory: `src/python_tools/`. Roles import toolkits via `role.add_toolkit(create_xxx_toolkit())`; they are bound automatically.

| Toolkit | Tools | Purpose |
|--------|------|------|
| `talk_toolkit` | `talk(target, message, urgency, wait?, attachment?)` | Asynchronous communication between roles (delivered to the other party's queue; **same-group members only**; use mail across groups) |
| `email_toolkit` | `send_email(to, subject, body, cc?)` / `read_mail(limit?, unread_only?)` / `open_mail(message_id)` / `mail_address_book(group?)` | Company mail (mail exchange between employees; virtual mailbox by default, real sending once SMTP is configured; mailbox = username@user suffix) |
| `talk_toolkit` | `talk(target, message, urgency, wait?, attachment?)` | Asynchronous communication between roles (delivered to the other party's queue; **same-group members only**; use mail across groups) |
| `email_toolkit` | `send_email(to, subject, body, cc?)` / `read_mail(limit?, unread_only?)` / `open_mail(message_id)` / `mail_address_book(group?)` | Company mail (mail exchange between employees; virtual mailbox by default, real sending once SMTP is configured; mailbox = username@user suffix) |
| `hr_toolkit` | `post_job_posting(requirement)` / `list_candidates()` | Post a job posting / list candidates (new-role creation and onboarding are done in the background) |
| `memory_toolkit` | `summary(content, day)` / `write_note(title, content, remind_tick, remind_day)` / `edit_note` / `list_notes` / `read_note` | Daily summary (switches to OFF_DUTY after saving) + notes (**with remind_tick = scheduled reminder**; merged from the former scheduled-task tool) |
| `todo_toolkit` | `todo_add(title, detail)` / `todo_list(status?)` / `todo_update(todo_id, status)` / `todo_delete(todo_id)` | Todo list (personal to-dos, id + status pending/in_progress/completed, persisted in data/todos/<role_id>.json) |
| `task_view_toolkit` | `my_tasks(scope?)` | Task list (pending queue + recent completed/failed history; read-only view) |
| `hermes_toolkit` | `hermes_new_conversation()` / `hermes_send(conversation_id, content)` | Call the Hermes Agent installed on the computer: creating a conversation returns a conversation id; sending a conversation synchronously waits for Hermes to finish and returns all results |
| `memory_toolkit` | `summary(content, day)` / `write_note(title, content, remind_tick, remind_day)` / `edit_note` / `list_notes` / `read_note` | Daily summary (switches to OFF_DUTY after saving) + notes (**with remind_tick = scheduled reminder**; merged from the former scheduled-task tool) |
| `todo_toolkit` | `todo_add(title, detail)` / `todo_list(status?)` / `todo_update(todo_id, status)` / `todo_delete(todo_id)` | Todo list (personal to-dos, id + status pending/in_progress/completed, persisted in data/todos/<role_id>.json) |
| `task_view_toolkit` | `my_tasks(scope?)` | Task list (pending queue + recent completed/failed history; read-only view) |
| `hermes_toolkit` | `hermes_new_conversation()` / `hermes_send(conversation_id, content)` | Call the Hermes Agent installed on the computer: creating a conversation returns a conversation id; sending a conversation synchronously waits for Hermes to finish and returns all results |
| `time_toolkit` | `get_time()` / `take_rest()` | View the shift time / take a rest (no parameters; enters ON_DUTY_IDLE; events wake it automatically) |
| `mcp_manager` | `mcp_search(keyword)` / `mcp_list()` / `mcp_add(tool_name)` / `mcp_remove(tool_name)` / `mcp_my_tools()` | Self-service management of MCP tools (search/add/remove local MCP tools; auto-attached to roles) |
| `client_toolkit` | `talk_to_client(message)` | Real-time communication with Client A (blocks waiting for user input) |

```python
ceo = system.get_role("ceo")
ceo.add_toolkit(create_client_toolkit())          # Client A tool
ceo.add_toolkit(create_memory_toolkit())          # summary/notes (add_toolkit binds automatically)
```

---

## MCP Loader

File: `src/python_tools/mcp_toolkit.py`. **Not auto-installed** — the user prepares the server with npx; the configuration only writes the package name.

### `MCPServer(package, args=None)`
A single server connection. `connect()` / `close()` / `list_tools()` / `call_tool(name, args)`.

### `MCPToolLoader(rules_file=None, server_args=None)`
| Method | Description |
|------|------|
| `load() -> dict[str, ToolKit]` | Connect to all servers → pull tools → group by rules |
| `list_loaded_tools()` | Details of loaded tools |
| `close()` | Close all connections |

### Configuration `src/config/mcp_group_rules.json`
```json
{
  "servers": ["@modelcontextprotocol/server-memory"],
  "groups": [{"name": "memory_ops", "match": ["*entity*", "create_relations"]}],
  "default_group": "default"
}
```

```python
from src.python_tools.mcp_toolkit import MCPToolLoader, load_mcp_toolkits
toolkits = load_mcp_toolkits()                    # one-click loading
dev.add_toolkit(toolkits["file_ops"])             # import a group
# or manage the lifecycle manually:
loader = MCPToolLoader()
toolkits = loader.load()
loader.close()
```

## LLM Client

File: `src/core/llm.py`

```python
OpenAICompatLLM(api_key=None, base_url=None, model=None)   # configuration: explicit args > -D system properties > environment variables > ConfigStore
# unified OpenAI interface, backend-agnostic; any OpenAI-compatible endpoint works (change base_url/model)
```

| Method | Parameter | Returns |
|------|------|------|
| `chat(system, user, max_tokens=512)` | str, str, int | `(text, tokens)` |
| `summarize(text)` | str | `(summary, tokens)` |
| `chat_with_tools(messages, tools)` | list, list | `(content, tool_calls, usage)` |
| `chat_with_tools(messages, tools)` | list, list | `(content, tool_calls, usage)` |

Environment variables: `OPENAI_API_KEY` (API key; may be omitted for key-free local endpoints), `OPENAI_BASE_URL` (default https://api.openai.com),
`OPENAI_MODEL` (default gpt-4o-mini); config-file keys `llm.api_key` / `llm.base_url` / `llm.model` may also be used.

---

## Complete Example

### Minimal multi-role system
```python
import os
os.environ["OPENAI_API_KEY"] = "sk-xxx"

from src.core.agent_system import AgentSystem
from src.core.types import Event, Priority

system = AgentSystem(role_ids=["ceo", "coo", "hr", "cfo"])
system.start()                                   # Tick 0 / Day 1, SHIFT_START: everyone on duty

system.trigger(Event(source="github", event_type="new_pr",
                     priority=Priority.HIGH,
                     payload={"pr_number": 188, "title": "fix: NPE"}))

# targeted delivery to the CEO
system.trigger(Event(source="client", event_type="requirements",
                     priority=Priority.HIGH, target_role="ceo",
                     payload={"instruction": "Collect requirements"}))

# CEO note reminder: Day 2, Tick 30 (notes and scheduled tasks are unified)
system.get_role("ceo").note_store.write_note(
    "Start writing the weekly report", "Weekly report template and this week's work summary", remind_tick=30, remind_day=2)
# CEO note reminder: Day 2, Tick 30 (notes and scheduled tasks are unified)
system.get_role("ceo").note_store.write_note(
    "Start writing the weekly report", "Weekly report template and this week's work summary", remind_tick=30, remind_day=2)

print(system.describe(), system.get_status())
system.stop()
```

### Adding a custom Python tool
```python
from src.core.tools import ToolKit

tk = ToolKit("my_tools", "custom tools")
def _ping(args):
    return "pong"
tk.add_python_tool("ping", "test tool", {"type": "object", "properties": {}}, _ping)

role = system.get_role("coo")
role.add_toolkit(tk)     # or register individually with role.add_mcp_tool(...)
```

### Loading MCP tools
```bash
npx -y @modelcontextprotocol/server-memory    # the user prepares the server first
```
```python
from src.python_tools.mcp_toolkit import load_mcp_toolkits
toolkits = load_mcp_toolkits()
system.get_role("coo").add_toolkit(toolkits.get("memory_ops"))
```

### Running the complete demo
```bash
cd AgentCompany && source .venv/bin/activate
OPENAI_API_KEY=sk-xxx python -m src.main        # multi-day loop demo
OPENAI_API_KEY=sk-xxx python -m src.role_demo   # role concurrency demo
OPENAI_API_KEY=sk-xxx python -m src.talk_demo   # role communication demo
```

---

## Web UI (Java version, group chat + Client A conversation)

The Java version ships a zero-dependency built-in Web UI (`com.sun.net.httpserver` + Jackson, no new dependencies):

| Class | Description |
|---|---|
| `web/ChatStore` | Chat message store (talk / client message types, monotonic seq) + Client A conversation coordination (beginClientWait / awaitClientReply / postClientReply); each `AgentSystem` owns one (`AgentSystem.chatStore`) |
| `web/ChatWebServer` | HTTP server: static assets `/web/*` + `GET /api/state` / `GET /api/messages?since=N` / `POST /api/reply` / `POST /api/attach`; port `AGENTCOMPANY_WEB_PORT` (default 8787) |
| `demo/WebDemo` | Lightweight demo: minimal team + preset messages + one Client A conversation |

Message sources: when the `talk` tool delivers successfully, the in-group message is
recorded; `talk_to_client` records the conversation between the Leadership Group and
Client A. Input-box enabling conditions (Web frontend): the "Leadership Group" is
selected and `clientTalk.active` is true (a member is waiting for Client A's reply).
When the Web UI is mounted, `talk_to_client` replies through the web page (timeout
`AGENTCOMPANY_CLIENT_REPLY_TIMEOUT`, default 20 minutes); otherwise it falls back to
the console `System.in`.
