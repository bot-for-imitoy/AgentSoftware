# Structured Inventory — `com.agent.software` (branch `master`)

Scope: `computers/`, `store/`, `services/MailService`, `web/`, `io/`, `utils/Json`, `core/MCPServer`.
Format per class: one-line purpose; `signature — meaning`; **External** (paths/env/HTTP/subprocess/libs); **Persisted** (owned file format).

## `computers/`

### `Computer` (abstract, `computers/Computer.java`, 443 lines)
Purpose: abstract one-machine-per-role base class; shared path constants (`COMPUTERS_ROOT="./data/computers"`, `DRIVE_ROOT="./data/drive"`, `DEFAULT_IMAGE="maf-base:latest"`, `CONTAINERFILE="Containerfile"`), MCP tool registry, and subprocess helper.
- `public final String roleId` — role key; also used to derive container name and host directories.
- `public List<String> ensureMcpServers()` — base returns `[]` and logs a warning; subclasses override to start servers.
- `public boolean isAutoMcp()` — whether the computer is auto-created (auto MCP start).
- `public String hostDir()` — host directory mounted as the workdir; base returns `""`.
- `public boolean uninstallMcpTool(String)` — removes a tool from the in-memory map.
- `public List<String> listInstalledMcpTools()` — sorted tool names.
- `public ToolRegistry.ToolDef getMcpTool(String)` / `public List<ToolRegistry.ToolDef> iterMcpTools()` — registry access.
- `public String runMcpTool(String, Map<String,Object>)` — invokes `ToolDef.handler`, returns text or an error string.
- `public boolean isOn()` — power flag.
- `public String reboot()` — `powerOff()` then `powerOn()`.
- `public String workdir()` — default `/home/agent`; `public String driveRoot()` — default `/mnt/drive`.
- `public String describe()` — status text for the LLM.
- Abstract: `powerOn()`, `powerOff()`, `runCommand(String,int,int)`, `readFile(String)`, `writeFile(String,String)`, `listDir(String)`, `deleteFile(String)`.
- `protected boolean mcpServersAlive()` — `isAlive(5.0)` over all sessions (empty ⇒ true).
- `protected void reconnectMcpServers()` — `close()` + `connect()` on dead sessions, reusing the same `MCPServer` objects.
- `protected static ProcessResult runProcess(List<String>, String stdin, int timeoutSeconds)` — subprocess with 2 virtual reader threads, writes child stdout/stderr to `System.out`/`System.err`, timeout ⇒ `destroyForcibly()` (rc `-1`), bounded 30 s reader join, rc `-2` start failure / `-3` interrupt.
- `protected static String truncate(String,int)`; `protected String formatResult(ProcessResult,int)` — `[exit N]` + truncated output.
- Protected mutable state: `on`, `autoMcp`, `mcpTools` (`LinkedHashMap`, not synchronized), `mcpServers` (`ArrayList`), `connectError`.
**External**: `System.getenv`, `System.out`/`System.err`, `ProcessBuilder` subprocesses, `java.nio.file` (LocalComputer only). No file format of its own.

Nested `public static final class LocalComputer extends Computer` — local-directory simulation.
- `LocalComputer(String roleId, boolean autoMcp, String baseDir, String driveDir, String name, String username, int uid)` — creates `data/computers/<roleId>/` eagerly, powered on by default; public mutable fields `name`, `username`, `uid`.
- Overrides `hostDir`, `workdir`, `driveRoot`, `powerOn`, `powerOff`, `runCommand`, `readFile`, `writeFile`, `listDir`, `deleteFile`.
**External**: local filesystem; `sh -c` subprocess. **Persisted**: arbitrary agent files under the resolved local directory.

### `ComputerManager` (`computers/ComputerManager.java`, 360 lines)
Purpose: per-system role-computer registry/factory plus podman network and base-image lifecycle.
- `public ComputerManager()` — independent registry (`computers`, `names` maps); `public static ComputerManager getInstance()` — process-level singleton `INSTANCE`.
- `public static Computer createComputer(String kind, String roleId, boolean autoMcp, Map<String,Object> kwargs)` — factory: `"local"` | `"ssh"` (throws without `host`) | default `"podman"`.
- `public String ensureNetwork()` — idempotent `podman network exists`/`create` under a `ReentrantLock`; returns `networkName`; skipped when podman is absent.
- `public boolean imageExists()` — `podman image exists maf-base:latest`.
- `public String ensureBaseImage()` — double-checked build under a cross-process `FileLock`, polls for the image otherwise (60 s rounds); returns the image name.
- `public Computer create(String kind, String roleId, String name, boolean autoMcp, Map<String,Object> kwargs)` — `ensureNetwork` + factory + register.
- `public void register(Computer, String name)` — registers by `roleId` and optional display name.
- `public Computer get(String roleId)` — throws `IllegalArgumentException` when missing.
- `public String nameOf(String roleId, String def)` / `public List<Computer> listAll()`.
- `public boolean destroy(String roleId)` — powerOff, `podman rm -f <container>` for podman computers, unregister.
- `public List<Map<String,String>> listLanDevices()` — person/role_id/computer/ip rows via `PodmanComputer.getLanIp()`, SSH host, or `local-<role>`.
**External**: `podman` subprocess (`network exists|create`, `image exists`, `build`, `ps`, `run`, `rm`), `PATH` (via `PodmanComputer.findExecutable`), project-root `Containerfile`. **Persisted**: lock file `data/.maf-base-image.lock` (empty marker, created/opened with `CREATE|WRITE`).

### `PodmanComputer` (`computers/PodmanComputer.java`, 441 lines)
Purpose: default container-backed computer; runs commands via `podman exec` and hosts in-container MCP servers (constants `MCP_FILESYSTEM_PACKAGE="@modelcontextprotocol/server-filesystem"`, `MCP_BROWSER_PACKAGE="@playwright/mcp"`, `MCP_BROWSER_BIN="/usr/local/bin/playwright-mcp"`).
- `PodmanComputer(String roleId, String image, boolean autoMcp, String username, int uid, String name)` — derives `maf-<roleId>` container name, `isCeo = roleId.equalsIgnoreCase("CEO")`; throws `RuntimeException` if `podman` is not on `PATH`.
- `public static String findExecutable(String exe)` — scans `PATH` for an executable file.
- `public static String shlexQuote(String)` — POSIX single-quote quoting.
- `public String hostDir()` — absolute `./data/computers/<roleId>`.
- `public String workdir()` — `/home/<username>`.
- `public String getLanIp()` — `podman inspect -f {{(index .NetworkSettings.Networks "<net>").IPAddress}}`; network name read from `ComputerManager.getInstance().networkName`.
- `public List<String> ensureMcpServers()` — when `autoMcp`, `ensureContainer()` then starts filesystem + Playwright stdio sessions via `podman exec -i --user <u>`; returns registered tool names.
- `public void ensureContainer()` — creates host dirs, `podman run -d` with `-v host:workdir`, `-v data/drive:/mnt/drive`, `-v data/computers/.npm-cache:/root/.npm`, 3 retries; idempotent `useradd`/sudoers/home setup; `/mnt/drive/Public` + per-user drive dir with chown; global `npm install -g` of both MCP packages (cached by `mcpPkgInstalled`).
- `public String powerOn()` / `public String powerOff()` — `ensureContainer` + `reconnectMcpServers`, or `podman stop`.
- `public String runCommand(String,int,int)` — `podman exec --user <u> <container> sh -c <cmd>`.
- `public String readFile(String)` / `public String writeFile(String,String)` / `public String listDir(String)` / `public String deleteFile(String)` — argv-passed `execArgv` scripts (`cat -- "$1"`, `cat > "$1"`, `ls -la -- "$1"`, `rm -f -- "$1"`).
- `public String describe()` — container-aware status text.
- `protected ProcessResult pod(String... args)` / `protected ProcessResult pod(int timeoutSeconds, String... args)` — `podman` invocation, 60 s default timeout.
- `protected String execArgv(String script, String... args)` — `podman exec --user <u> <c> sh -c <script> sh <args>`; 60 s timeout.
**External**: `podman` subprocess, host filesystem, `PATH`, in-container `node`/`npm`/`playwright-mcp`, `useradd`/`chown`/`chmod`/`mkdir` in-container. **Persisted**: container filesystem under `./data/computers/<roleId>` + `./data/drive` + npm cache; no app-owned format.

### `SSHComputer` (`computers/SSHComputer.java`, 136 lines)
Purpose: remote computer over `ssh`; no container.
- `SSHComputer(String roleId, String host, String user, String keyPath, int port, boolean autoMcp, String name)` — throws when `host` is empty; public final `name`, `host`, `user`, `keyPath`, `port`.
- `public String workdir()` — `~/maf-<roleId>`.
- `protected String ssh(String remoteCmd, int timeout, int maxChars)` — runs the wrapped remote command.
- `public String powerOn()` — `echo ok` probe sets `on`; `public String powerOff()` — flips `on` only.
- `public String runCommand(String,int,int)` / `readFile` / `writeFile` / `listDir` / `deleteFile` — `cat`, `mkdir -p && cat >`, `ls -la`, `rm -f`, quoted via `PodmanComputer.shlexQuote`.
**External**: `ssh -p <port> -o StrictHostKeyChecking=no -o ConnectTimeout=10 [-i key] user@host "mkdir -p … && cd … && <cmd>"`; remote filesystem. **Persisted**: remote `~/maf-<roleId>/`.

## `store/`

### `ConfigStore` (`store/ConfigStore.java`, 139 lines)
Purpose: dot-path JSON config object held in memory and saved on every mutation.
- `ConfigStore(Path path)` / `ConfigStore()` — default path `PathManager.createDefault().configFile("config.json")`.
- `public Path getPath()` / `public Map<String,Object> data()` (copy).
- `public boolean exists()` / `public Map<String,Object> load()` — throws on unreadable/invalid JSON or non-object root.
- `public Path save()` — `Json.atomicWrite` pretty JSON.
- `public Object get(String key, Object def)` — `Json.getByPath`.
- `public Object set(String key, Object value)` — deep-copies, writes, saves immediately, returns copy.
- `public Map<String,Object> update(Map<String,Object> values)` — batch dot-path sets + one save.
- `public boolean delete(String key)` — removes leaf, saves when present.
**External**: filesystem only. **Persisted**: `config.json` under `PathManager.configDir()` (platform/`AGENTSOFTWARE_CONFIG_DIR` dependent), flat JSON object with nested objects for dot paths.

### `StateStore` (`store/StateStore.java`, 359 lines)
Purpose: single-file snapshot/restore of roles, tasks, conversations, computer bindings, and simulated time (`DEFAULT_STATE_FILE="./data/state.json"`, `VERSION=1`).
- `StateStore(String path)` / `StateStore()` — default `./data/state.json`.
- `public boolean exists()`.
- `public String save(AgentSystem system)` — `collect()` + `Json.atomicWrite`; logs role/history counts; returns path.
- `public int restore(AgentSystem system)` — returns 0 when missing, unparsable, or `version != 1`; otherwise `apply()`.
- Private `collect` / `roleToDict` / `apply` / `restoreRole` / `restoreComputers`.
- `restoreComputers` rebuilds computers on a `newVirtualThreadPerTaskExecutor` with `Semaphore(10)` and blocks up to 10 minutes, calling `comp.powerOn()`.
- `restoreRole` rebuilds roles absent from the pool via `AgentRole.builder()` + `system.addRole`, overwrites profile fields, restores `role.conversation()`.
**External**: filesystem; drives `ComputerManager.create` + `Computer.powerOn` (podman). **Persisted**: `data/state.json` = `{version, saved_at, time{day,tick_of_day,base_date}, roles[{role_id,name,title,responsibilities,personality,skills,interest_keywords,system_prompt_extra,is_default,state,salience_threshold,computer_kind,computer_kwargs,pending_tasks,history,conversation?}], computers{roleId{kind,auto_mcp,name}}}`.

### `NoteStore` (`store/NoteStore.java`, 273 lines)
Purpose: per-role markdown notes and daily summaries, with time-bus reminders.
- `NoteStore(String baseDir, String roleId, TimeEventBus timeManager)` — default base `data/notes`; creates `notes/` and `summaries/` eagerly; public `roleId`, `notePath`, `summaryPath`.
- `public static String sanitizeTitle(String)` — regex `[\\/:*?"<>|#%\s'`$;&]+` → `_`, empty ⇒ `untitled`.
- `public static String noteFilename(String)` — `<sanitized>.md`; `public static String summaryFilename(int)` — `<day>.md`.
- `public TimeEventBus.ScheduledTask scheduleReminder(String title, int tick, Integer day)` — requires a bound `TimeEventBus`, payload `note_title`.
- `public boolean cancelReminder(String title)` / `public Map<String,Object> getReminder(String title)` — scans `timeManager.listTasks(roleId)` by payload.
- `public Path writeNote(String title, String content, Integer remindTick, Integer remindDay)` — overwrite, optional reminder.
- `public Path editNote(...)` — overwrite, resets the reminder when given.
- `public List<String> listNotes()` / `public String readNote(String)` (null if absent) / `public boolean deleteNote(String)`.
- `public Path saveSummary(String content, Integer day)` / `public String getSummary(Integer day)` / `public String getLatestSummary(Integer beforeDay)`.
**External**: filesystem; `TimeEventBus` (in-memory scheduler). **Persisted**: `data/notes/<roleId>/notes/<title>.md` and `data/notes/<roleId>/summaries/<day>.md`.

### `TodoStore` (`store/TodoStore.java`, 138 lines)
Purpose: per-role JSON todo list (`TODO_STATUSES = ["pending","in_progress","completed"]`).
- `TodoStore(String roleId, String path)` — default `./data/todos/<roleId|shared>.json`; public final `roleId`.
- `public Map<String,Object> add(String title, String detail)` — new item `{id (8 hex), title, detail, status:"pending", created_at, updated_at}` (epoch seconds as double); persists whole list.
- `public List<Map<String,Object>> list(String status)` — optional exact status filter.
- `public Map<String,Object> update(String todoId, String status)` — validates status, returns updated item or null.
- `public boolean delete(String todoId)`.
**External**: filesystem only. **Persisted**: `data/todos/<roleId>.json` = JSON array of item objects.

### `PathManager` (`store/PathManager.java`, 196 lines)
Purpose: cross-platform application directory resolver; the project's nominal single path entry point.
- `PathManager(String appName)` / `PathManager(String appName, String envPrefix, Map<String,String> env, String platform)` — env/platform injectable for tests; `public static PathManager createDefault()` → app name `AgentSoftware`.
- `public Path configDir()` / `dataDir()` / `cacheDir()` / `logDir()` — overrides `<PREFIX>_CONFIG_DIR|_DATA_DIR|_CACHE_DIR|_LOG_DIR`, then OS conventions (Windows AppData, macOS Application Support/Caches/Logs, Linux XDG or `~/.config|.local/share|.cache|.local/state`).
- `public Path configFile(String...)` / `dataFile` / `cacheFile` / `logFile` — resolve child parts.
- `public void ensureDirs()` — creates all four; `public Path ensure(Path)` — creates the parent; `public String getAppName()`.
- Private `isWindows`/`isMacos`/`getEnv`/`home`/`appdata`.
**External**: env vars `HOME`, `USERPROFILE`, `APPDATA`, `LOCALAPPDATA`, `XDG_*`, `<PREFIX>_*`, OS name, system properties, filesystem. No persisted format.

## `services/`

### `MailService` (`services/MailService.java`, 491 lines)
Purpose: internal virtual mailboxes plus optional real SMTP sending (`DEFAULT_MAIL_SUFFIX="company.com"`, `DEFAULT_MAIL_DIR="data/mail"`, `MAILBOX_FILE="mailboxes.json"`).
- `public interface MailDeliveryListener { void mailDelivered(MailMessage, String recipientMailbox); }` — post-delivery hook, one call per deduped lowercased recipient.
- `public void setDeliveryListener(MailDeliveryListener)` — volatile single field.
- Nested `public static final class MailConfig` — `suffix`, `smtpHost`, `smtpPort=587`, `smtpUser`, `smtpPassword`, `smtpFrom`, `Boolean useSsl`, `dataDir`; `public static MailConfig fromEnv()` reads `MAIL_SUFFIX`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_FROM`, `SMTP_USE_SSL`, `MAIL_DATA_DIR`; `public String mode()` → `"smtp"` when `smtpHost` non-empty else `"virtual"`.
- Nested `public static final class MailMessage` — fields `messageId`, `senderEmail`, `senderName`, `subject`, `body`, `recipients`, `cc`, `timestamp` (epoch seconds, double), `read`, `viaSmtp`; `public static MailMessage create(...)` (12-char id), `toDict()`, `fromDict(Map)`, `preview()`, `fullText()`.
- `MailService(MailConfig, String dataDir)` / `MailService()` — config defaults to `MailConfig.fromEnv()`.
- `public String emailFor(AgentRole role)` — explicit `role.email`, else `<username|roleId>@<suffix>` lowercased.
- `public String send(String senderEmail, String senderName, List<String> to, String subject, String body, List<String> cc)` — validates recipients/subject+body; SMTP path first when configured (failure aborts delivery); then appends an independent copy per To+CC mailbox under a private lock and saves; then notifies the listener outside the lock.
- `public List<MailMessage> inbox(String email, Integer limit)` — newest first, optional limit.
- `public int unreadCount(String email)` / `public MailMessage read(String email, String messageId)` — `read` marks read and saves.
- `public String describe()` — mode description.
- `public static MailService getMailService()` — lazy double-checked process-wide singleton; `public static void resetInstance()` — test reset.
**External**: filesystem; environment variables; `jakarta.mail` (SMTP host/port, STARTTLS on 587 or SSL on 465/`SMTP_USE_SSL`), 30 s connect/read timeouts, `Transport.send`. **Persisted**: `data/mail/mailboxes.json` = `{"mailboxes": {<lowercased addr>: [ {message_id,sender_email,sender_name,subject,body,recipients,cc,timestamp,read,via_smtp} ]}}` (atomic write, loaded once via `loaded` flag).

## `web/`

### `ChatWebServer` (`web/ChatWebServer.java`, 426 lines)
Purpose: JDK `com.sun.net.httpserver` UI + JSON API over an `AgentSystem`'s `ChatStore` (`DEFAULT_HOST="0.0.0.0"`, `DEFAULT_PORT=8787`, `DEFAULT_REPLY_TIMEOUT_MS=20 min`; static `MIME`/`GROUP_LABELS` maps).
- `public static String configuredHost()` / `public static int configuredPort()` / `public static long replyTimeoutMs()` — env var first (`AGENTSOFTWARE_WEB_HOST|WEB_PORT|CLIENT_REPLY_TIMEOUT`), then system property, then default.
- `ChatWebServer(AgentSystem system)` / `ChatWebServer(AgentSystem, String host, int port)` — stores `system.chatStore`; port `0` ⇒ random; context `/`; executor `newVirtualThreadPerTaskExecutor()`.
- `public void start()` / `public void stop()` / `public int port()` (actual) / `public String host()`.
- Private handlers: `handle`, `handleApi`, `apiState`, `buildGroups`, `addMember`, `labelFor`, `apiMessages`, `handleReply`, `handlePause`, `handleStatic`, `readResource`, `sendJson`.
**External**: HTTP server on `host:port` (no auth, `0.0.0.0` by default), classpath static resources `web/index.html`, `web/app.js`, `web/style.css`, env vars/system properties. Reads `system.day()/tick()`, `system.timeManager.*`, `system.pool.allRoles()`, `system.describe()/isPaused()/pauseReason()/pause()/resume()`, plus static `RoleLoader.TEMPLATES` and `Toolkits.LEADERSHIP_GROUP`. **Persisted**: none.
Endpoint list (exact):
- `GET /` → `index.html`; `GET /<name>` → classpath `web/<name>` (rejects names containing `..` or `\`).
- `GET /api/state` — day/tick/tickOfDay/date/time/datetime/describe/paused/pauseReason, `web{host,port,url,attached}`, `groups[]`, `clientTalk{active,holderName,holderRoleId}`.
- `GET /api/messages?since=N` — `{ok,lastSeq,messages[]}`.
- `POST /api/reply` — body `{"text":...}`; 400 empty/invalid, 409 no pending dialogue, 200 with recorded message.
- `POST /api/pause` / `POST /api/resume` — optional `{"reason":...}`; 405 on wrong method.
- `POST /api/attach` — heartbeat ack.
- Any unknown `/api/*` → 404 `{"ok":false,...}`; every `/api/*` call calls `store.markAttached()`.

### `ChatStore` (`web/ChatStore.java`, 269 lines)
Purpose: in-memory chat log (web data source) plus the client-reply rendezvous.
Constants: `MAX_HISTORY=5000`, kinds `KIND_TALK|CLIENT|REASON|NOTE|TOOL|ANSWER`, `CLIENT_NAME="Client A"`, `ATTACH_TTL_MS=60000`.
- Nested `public static final class ChatMessage` — public fields `seq, ts, kind, group, fromRoleId, fromName, toRoleId, toName, text, urgency, extra`.
- `public ChatMessage record(kind, group, fromRoleId, fromName, toRoleId, toName, text, urgency)` / `record(..., Map<String,Object> extra)` — synchronized on `history`, assigns `++lastSeq`, evicts oldest beyond `MAX_HISTORY`.
- `public long lastSeq()` / `public List<Map<String,Object>> messagesSince(long sinceSeq)` / `public static Map<String,Object> toMap(ChatMessage)`.
- `public synchronized String beginClientWait(String roleId, String name, String group)` — single-slot; errors if another member already waits.
- `public synchronized boolean isClientWaitPending()` / `pendingHolderName()` / `pendingHolderRoleId()` / `pendingGroup()`.
- `public synchronized ChatMessage postClientReply(String text)` — records a `client` message and wakes the waiter; null when none pending.
- `public synchronized String awaitClientReply(long timeoutMs)` — waits until reply/deadline, null on timeout/interrupt.
- `public synchronized void endClientWait()` — clears state and notifies.
- `public void markAttached()` / `public boolean isAttached()` — lock-free heartbeat against `ATTACH_TTL_MS`.
**External**: none (pure in-memory). **Persisted**: none.

## `io/`

### `Input` (abstract, `io/Input.java`, 51 lines)
Purpose: client/user input channel abstraction per `AgentSystem`.
- `public static final String ERROR_PREFIX = "talk_to_client: Error: "` — error-string namespace owned by a tool package.
- `public static String error(String detail)` / `public static boolean isError(String result)`.
- `public boolean isWebPage()` — default false.
- `public abstract String read(String target)` — blocks for one input line; null = closed; `error(...)` string = channel failure.

### `StdInput` (`io/StdInput.java`, 29 lines)
Purpose: console channel reading `System.in`.
- `public String read(String target)` — new `BufferedReader` per call; returns null on EOF; `Input.error(...)` on `IOException`; ignores `target`.
**External**: stdin.

### `WebInput` (`io/WebInput.java`, 97 lines)
Purpose: web channel that registers a pending wait on `ChatStore` and blocks for a reply.
- `WebInput()` / `WebInput(ChatStore, ClientCommunicationLock)` / `public void bind(ChatStore, ClientCommunicationLock)`.
- `public boolean isWebPage()` — true.
- `public String read(String target)` — errors when unbound, target blank, store not attached, or lock not held; `beginClientWait(roleId,name,target)` then `awaitClientReply(ChatWebServer.replyTimeoutMs())`; timeout ⇒ error string; always `endClientWait()`.
**External**: `ChatStore`, `ClientCommunicationLock.holderRoleId()/holderName()`, static `ChatWebServer.replyTimeoutMs()`. **Persisted**: none.

## `utils/`

### `Json` (`utils/Json.java`, 186 lines)
Purpose: project-wide Jackson wrapper keyed to `Map<String,Object>`/`List<Object>` structures.
- `public static Map<String,Object> parseObject(String)` / `public static Object parse(String)`.
- `public static String stringify(Object)` — compact, single-line (deliberately no `INDENT_OUTPUT`, MCP stdio safety).
- `public static String stringifyPretty(Object)`.
- `public static String readFile(Path)` — UTF-8.
- `public static void atomicWrite(Path, String content)` — `createDirectories(parent)`, write `.<name>.tmp` sibling, `Files.move(..., REPLACE_EXISTING)`.
- `public static Object getByPath(Map<String,Object>, String dotPath, Object def)` — deep-copies the result.
- `public static Object deepCopy(Object)` — `parse(stringify(v))`, returns the original on failure.
- `public static String asText(Object)`; `public static String str(Map,String,String)`; `public static int intVal(...)`; `public static double doubleVal(...)`; `public static boolean boolVal(...)` (`1/true/yes/on`); `public static List<String> strList(Map,String)`.
**External**: single static `ObjectMapper`; filesystem via `atomicWrite`/`readFile`. No owned format.

## `core/`

### `MCPServer` (`core/MCPServer.java`, 310 lines)
Purpose: JSON-RPC 2.0 MCP client over a child process's stdio (newline-delimited).
Constant: `PROTOCOL_VERSION="2024-11-05"`.
- `public final String packageName`, `public final List<String> args`, `public final String command`, `public final List<String> commandArgs`.
- `MCPServer(String packageName, List<String> args, String command, List<String> commandArgs)` / `MCPServer(String packageName, List<String> args)`.
- `public synchronized void connect()` — launches `command+commandArgs` when set, else `npx -y <package> <args>`; starts daemon `mcp-<pkg>` reader and stderr-drain threads; `initialize` handshake (clientInfo `maf-java/1.0.0`) then `notifications/initialized`; sets `connected`; failures recorded in `connectError`, never thrown.
- `public synchronized void close()` — `destroy()` + 2 s wait, completes all pending futures with null, clears `pending`.
- `public boolean isAlive(double timeoutSeconds)` — false unless connected/alive; one `tools/list` round trip.
- `public String connectError()` — last failure text.
- `public List<Map<String,Object>> listTools()` — `tools/list`, returns `[]` on failure.
- `public String callTool(String name, Map<String,Object> arguments)` — `tools/call`, concatenates `content[].text`, prefixes `[MCP Error]` on `isError`, falls back to `String.valueOf(result)`.
- Private `notify`, `request(method,params)` (60 s), `request(..., timeoutMillis)`, `sendLine` (synchronized), `readLoop`, `handleMessage`.
**External**: subprocess (`npx` or arbitrary command such as `podman exec -i`), stdin/stdout/stderr pipes, `slf4j`. **Persisted**: none (`pending`, `nextId`, `connected` in memory).

## Cross-cutting concerns and hidden couplings

1. **Two competing global singletons.** `ComputerManager.INSTANCE`/`getInstance()` and `MailService.getMailService()` are process-wide; a `ComputerManager` instance's `networkName` is ignored by `PodmanComputer.getLanIp()` and `PodmanComputer.ensureContainer()`, which both call `ComputerManager.getInstance()`. A non-default manager therefore still uses the singleton's network for containers.
2. **Static `Json.MAPPER`** (`ObjectMapper`) shared by every store, LLM, and MCP path; comment in-file warns against enabling `INDENT_OUTPUT` because `stringify` feeds MCP stdio framing.
3. **Two path regimes coexist.** `PathManager` resolves platform dirs from env/XDG (and backs only `ConfigStore`'s default), while `StateStore`, `NoteStore`, `TodoStore`, `MailService`, and both computers hard-code relative `./data/...` paths. `Computer.COMPUTERS_ROOT`/`DRIVE_ROOT` are string constants referenced from at least three classes.
4. **Hard-coded paths/ports/images:** `./data/computers`, `./data/drive`, `./data/computers/.npm-cache`, `./data/state.json`, `data/notes`, `./data/todos`, `data/mail`, `data/.maf-base-image.lock`, `maf-base:latest`, `Containerfile`, `maf-net`, container name `maf-<roleId>`, and in-container `/home/<username>`, `/mnt/drive`, `/mnt/drive/Public`, `/root/.npm`, `/usr/local/bin/*`.
5. **Cross-process locking is limited to one file.** Only `ComputerManager` uses `FileLock` (`data/.maf-base-image.lock`). `ConfigStore`, `StateStore`, `NoteStore`, `TodoStore`, and `MailService` do unlocked read-modify-write; `Json.atomicWrite` derives a fixed `.<name>.tmp` sibling, so concurrent writers to one file share and can clobber the same temp file.
6. **Thread ownership/executors.** `Computer.runProcess` starts two virtual threads per subprocess and joins them with a hard 30 s deadline, then reports rc `-1` on timeout (podman descendants may hold the pipe). `StateStore.restoreComputers` uses a virtual-thread executor with `Semaphore(10)` and a 10-minute `awaitTermination`. `ChatWebServer` uses an unbounded virtual-thread executor. `MCPServer` uses two daemon platform threads per session plus `ConcurrentHashMap<Integer,CompletableFuture>` for request matching.
7. **Unmanaged shared state.** `Computer.mcpTools`/`mcpServers`/`connectError` and `PodmanComputer.mcpPkgInstalled` are non-synchronized mutable fields; `Computer.connectError` is written by subclasses while `PodmanComputer` shadows the concept with its own `private mcpPkgInstalled` and sets the inherited field. `ChatStore` is a `synchronized` monitor plus a separate `synchronized(history)` block, i.e. two lock objects for related state.
8. **HTTP surface is unauthenticated and binds all interfaces by default** (`0.0.0.0:8787`), serves classpath `/web/*` with only a literal `..`/`\` name check, and turns any `/api/*` poll into the 60 s attach heartbeat. Client replies are a single global slot per system (`ChatStore.beginClientWait` rejects a second waiter), so concurrent `talk_to_client` calls cannot both wait.
9. **Layering inversions.** `io.WebInput` (generic channel) calls `web.ChatStore`, `tools.toolkits.client.ClientCommunicationLock`, and static `web.ChatWebServer.replyTimeoutMs()`; its error strings are hard-prefixed with `talk_to_client: Error: `. `web.ChatWebServer.buildGroups()` reaches into static `RoleLoader.TEMPLATES` and instantiates template roles via `Supplier.get()` on every `/api/state` request.
10. **Mail semantics.** SMTP failure returns before internal delivery, so the virtual mailbox copy is skipped when real sending fails; the internal copy is added even in SMTP mode. The delivery listener is a single volatile field (last `setDeliveryListener` wins) and is invoked outside the mailbox lock after `save()`.
11. **StateStore/ConfigStore validation.** `StateStore.restore` discards the entire archive on any version mismatch (`!= 1`) and unchecked-casts `roles`/`history`; `save` counts history with an unchecked cast. `ConfigStore.set`/`update` persist on every single key write (no batching unless `update` is used).
12. **Identifier/derivation collisions.** `PodmanComputer.isCeo` is string equality on role id `"CEO"`; `TodoStore` ids are the first 8 hex chars of a UUID; `NoteStore.sanitizeTitle` maps many distinct titles to the same file name (silent overwrite); `LocalComputer` uses `"shared"` when `roleId` is empty, `PodmanComputer` container name likewise.
13. **HTTP endpoints enumerated above** (7 routes + static + 404); no `/api/shutdown`, no health endpoint, no CORS headers.
14. **Subprocess surface:** host `podman`, `npx`, `ssh`, `sh -c`; in-container `sh`, `node`, `npm`, `playwright-mcp`, `useradd`, `chown`, `chmod`, `mkdir`. `ssh` uses `StrictHostKeyChecking=no` unconditionally.
15. **Persistence formats owned:** `data/state.json` (v1, StateStore), `data/todos/<role>.json` (TodoStore), `data/notes/<role>/{notes,summaries}/*.md` (NoteStore), `data/mail/mailboxes.json` (MailService), `config.json` under PathManager config dir (ConfigStore), `data/.maf-base-image.lock` (ComputerManager). MCP tool registrations and chat history are memory-only.
