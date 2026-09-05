package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.tools.Toolkit;
import com.agent.software.tools.toolkits.talk.Talk;
import com.agent.software.computers.Computer;
import com.agent.software.computers.ComputerManager;
import com.agent.software.event.TimeEventBus;
import com.agent.software.core.Types;
import com.agent.software.llm.LLM;
import com.agent.software.services.MailService;
import com.agent.software.store.NoteStore;
import com.agent.software.store.TodoStore;
import com.agent.software.tools.Toolkits;
import com.agent.software.tools.toolkits.client.ClientCommunicationLock;
import com.agent.software.tools.toolkits.mcp.MCPManager;
import com.agent.software.tools.toolkits.skill.SkillManager;
import com.agent.software.utils.Json;
import com.agent.software.web.ChatStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core of the role system: AgentRole (a single role) — the Java counterpart of the Python roles.py.
 *
 * Each role has: a persona (name/title/responsibilities/skills), a thread-safe priority task queue,
 * its own LLM session (role-specific System Prompt), and its own worker thread.
 */
public class AgentRole {

    private static final Logger logger = LoggerFactory.getLogger(AgentRole.class);

    // ── Tool-calling loop limits ────────────────────────────────
    public static final int MAX_TOOL_ROUNDS = 20;               // max tool-calling rounds
    public static final Integer MAX_TOOL_TOTAL_TOKENS = null;   // cumulative per-task token cap (currently lifted)

    // ── Role activity journal ──────────────────────────────────
    public static Path JOURNAL_DIR = Paths.get("data/journals");
    private static final Object JOURNAL_LOCK = new Object();

    /** Tool-calling loop exceeded or LLM call failed. The task should be marked failed. */
    public static class ToolLoopError extends RuntimeException {
        public ToolLoopError(String message) {
            super(message);
        }
    }

    /** Task urgency — higher value means more urgent, processed first. */
    public enum Urgency {
        LOW(1), NORMAL(3), HIGH(6), CRITICAL(10);

        public final int value;

        Urgency(int value) {
            this.value = value;
        }

        public static Urgency from(int value) {
            for (Urgency u : values()) {
                if (u.value == value) {
                    return u;
                }
            }
            return NORMAL;
        }
    }

    /** Task status: pending|running|done|failed. */
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    /** A task in the role's queue. Popped by descending urgency. */
    public static final class Task {
        public int urgency;                        // positive urgency (queue pops in descending order)
        public String taskId;
        public String description = "";
        public String source = "";
        public Map<String, Object> context = new LinkedHashMap<>();
        public double createdAt;
        public String status = STATUS_PENDING;
        public String result = "";
        public int tokensConsumed = 0;
        public String assignedRole = "";
        private final long seq;                    // stable FIFO ordering among equal urgencies

        private static long seqCounter = 0;

        public Task(int urgency, String taskId, String description, String source,
                    Map<String, Object> context, String status, String result,
                    int tokensConsumed, double createdAt, String assignedRole) {
            this.urgency = Math.max(1, urgency);
            this.taskId = taskId != null ? taskId : UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            this.description = description != null ? description : "";
            this.source = source != null ? source : "";
            this.context = context != null ? context : new LinkedHashMap<>();
            this.status = status != null ? status : STATUS_PENDING;
            this.result = result != null ? result : "";
            this.tokensConsumed = tokensConsumed;
            this.createdAt = createdAt;
            this.assignedRole = assignedRole != null ? assignedRole : "";
            this.seq = ++seqCounter;
        }

        public Task(int urgency, String description, String source, Map<String, Object> context) {
            this(urgency, null, description, source, context, STATUS_PENDING, "", 0,
                    System.currentTimeMillis() / 1000.0, "");
        }

        /** Task → serializable Map (urgency stored as a positive number). */
        public Map<String, Object> toDict() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("urgency", urgency);
            m.put("task_id", taskId);
            m.put("description", description);
            m.put("source", source);
            m.put("context", new LinkedHashMap<>(context));
            m.put("status", status);
            m.put("result", result);
            m.put("tokens_consumed", tokensConsumed);
            m.put("created_at", createdAt);
            m.put("assigned_role", assignedRole);
            return m;
        }

        /** Map → Task. */
        @SuppressWarnings("unchecked")
        public static Task fromDict(Map<String, Object> d) {
            Object ctx = d.getOrDefault("context", new LinkedHashMap<>());
            return new Task(
                    Json.intVal(d, "urgency", 3),
                    Json.str(d, "task_id", ""),
                    Json.str(d, "description", ""),
                    Json.str(d, "source", ""),
                    ctx instanceof Map ? (Map<String, Object>) ctx : new LinkedHashMap<>(),
                    Json.str(d, "status", STATUS_PENDING),
                    Json.str(d, "result", ""),
                    Json.intVal(d, "tokens_consumed", 0),
                    Json.doubleVal(d, "created_at", System.currentTimeMillis() / 1000.0),
                    Json.str(d, "assigned_role", ""));
        }

        @Override
        public String toString() {
            return "Task(" + taskId + ", " + description + ", urgency=" + urgency + ", " + status + ")";
        }
    }

    /** Task completion callback (on_task_start / on_task_done). */
    @FunctionalInterface
    public interface TaskCallback {
        void call(AgentRole role, Task task);
    }

    // ── AgentRole fields ──────────────────────────────────────

    public String name;                                  // person name, e.g. "Zhang San"
    public String roleId = "";                           // functional role, e.g. "coder"
    public String username = "";                         // container/system username (pinyin)
    public int uid = 0;                                  // uid inside the container (1100 + registration seq)
    public String title = "";
    public String responsibilities = "";
    public String personality = "";
    public List<String> skills = new ArrayList<>();
    public String systemPromptExtra = "";
    public boolean isDefault = false;
    public String group = "";                            // group membership (talk in-group restriction)
    public String email = "";                            // explicit company email (optional)
    public String computerKind = "podman";
    public Map<String, Object> computerKwargs = new LinkedHashMap<>();

    // Event filter state (per-role)
    public Types.AgentState state = Types.AgentState.ON_DUTY_IDLE;
    public double salienceThreshold = 0.4;
    public Set<String> interestKeywords = new LinkedHashSet<>();

    // Internal state (managed by RolePool)
    private final PriorityQueue<Task> queue = new PriorityQueue<>(
            Comparator.comparingInt((Task t) -> -t.urgency).thenComparingLong(t -> t.seq));
    private final ReentrantLock lock = new ReentrantLock();
    private Task currentTask = null;
    private volatile boolean running = true;
    private LLM llm = null;                              // lazy initialization
    private ToolRegistry tools = null;                   // lazy initialization
    private RolePool pool = null;                        // back-reference used by talk
    private AgentSystem system = null;                   // owning AgentSystem (source of computer/mail/data dirs)
    private NoteStore noteStore = null;
    private TodoStore todoStore = null;
    private TimeEventBus timeManager = null;
    private Computer computer = null;

    // talk wait=true synchronous reply-waiting state
    private String waitingReplyFrom = null;
    private final ReentrantLock replyCondLock = new ReentrantLock();
    private final Condition replyCond = replyCondLock.newCondition();
    private String replyBox = null;
    private Types.AgentState stateBeforeWait = null;

    private final List<Task> taskHistory = new ArrayList<>();

    public TaskCallback onTaskStart = null;
    public TaskCallback onTaskDone = null;

    // ── Construction ──────────────────────────────────────────

    public AgentRole(String name, String roleId, String username, int uid, String title,
                     String responsibilities, String personality, List<String> skills,
                     String systemPromptExtra, boolean isDefault, String group, String email,
                     String computerKind, Map<String, Object> computerKwargs,
                     Types.AgentState state, double salienceThreshold, Set<String> interestKeywords) {
        this.name = name != null ? name : "";
        this.roleId = roleId != null ? roleId : "";
        this.username = username != null ? username : "";
        this.uid = uid;
        this.title = title != null ? title : "";
        this.responsibilities = responsibilities != null ? responsibilities : "";
        this.personality = personality != null ? personality : "";
        this.skills = skills != null ? new ArrayList<>(skills) : new ArrayList<>();
        this.systemPromptExtra = systemPromptExtra != null ? systemPromptExtra : "";
        this.isDefault = isDefault;
        this.group = group != null ? group : "";
        this.email = email != null ? email : "";
        this.computerKind = computerKind != null ? computerKind : "podman";
        this.computerKwargs = computerKwargs != null ? new LinkedHashMap<>(computerKwargs) : new LinkedHashMap<>();
        if (state != null) {
            this.state = state;
        }
        if (salienceThreshold > 0) {
            this.salienceThreshold = salienceThreshold;
        }
        this.interestKeywords = interestKeywords != null ? new LinkedHashSet<>(interestKeywords) : new LinkedHashSet<>();
        postInit();
    }

    /** Convenience constructor: create from a builder. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name = "";
        private String roleId = "";
        private String username = "";
        private int uid = 0;
        private String title = "";
        private String responsibilities = "";
        private String personality = "";
        private List<String> skills = new ArrayList<>();
        private String systemPromptExtra = "";
        private boolean isDefault = false;
        private String group = "";
        private String email = "";
        private String computerKind = "podman";
        private Map<String, Object> computerKwargs = new LinkedHashMap<>();
        private Types.AgentState state = null;
        private double salienceThreshold = 0;
        private Set<String> interestKeywords = null;

        public Builder name(String v) { name = v; return this; }
        public Builder roleId(String v) { roleId = v; return this; }
        public Builder username(String v) { username = v; return this; }
        public Builder uid(int v) { uid = v; return this; }
        public Builder title(String v) { title = v; return this; }
        public Builder responsibilities(String v) { responsibilities = v; return this; }
        public Builder personality(String v) { personality = v; return this; }
        public Builder skills(List<String> v) { skills = v; return this; }
        public Builder systemPromptExtra(String v) { systemPromptExtra = v; return this; }
        public Builder isDefault(boolean v) { isDefault = v; return this; }
        public Builder group(String v) { group = v; return this; }
        public Builder email(String v) { email = v; return this; }
        public Builder computerKind(String v) { computerKind = v; return this; }
        public Builder computerKwargs(Map<String, Object> v) { computerKwargs = v; return this; }
        public Builder state(Types.AgentState v) { state = v; return this; }
        public Builder salienceThreshold(double v) { salienceThreshold = v; return this; }
        public Builder interestKeywords(Set<String> v) { interestKeywords = v; return this; }

        public AgentRole build() {
            return new AgentRole(name, roleId, username, uid, title, responsibilities,
                    personality, skills, systemPromptExtra, isDefault, group, email,
                    computerKind, computerKwargs, state, salienceThreshold, interestKeywords);
        }
    }

    /**
     * Fill in derived fields: username (falls back to role_id when not given) and uid (user id in the container).
     * Template roles get their pinyin username directly from the username field in role_templates.json
     * (PinyinMap has been merged into the JSON); this only handles programmatically created roles.
     */
    private void postInit() {
        if (username == null || username.isEmpty()) {
            username = !roleId.isEmpty() ? roleId : "agent";
        }
        if (uid <= 0) {
            uid = 1100;  // assigned by RolePool.addRole at registration (1100 + registration seq)
        }
    }

    /** This employee's company email address (every member has a mailbox). */
    public String mailAddress() {
        return mailService().emailFor(this);
    }

    // ── Event Filter (per-role Layer 1-3) ──────────────────

    /**
     * Run the per-role 3-layer filter.
     *
     * @return a {accepted, reason} pair.
     */
    public Map.Entry<Boolean, String> evaluateEvent(Types.Event event) {
        // Layer 1: State Mask (WAIT and OFF_DUTY treated alike)
        if (state == Types.AgentState.OFF_DUTY || state == Types.AgentState.WRAPPING_UP
                || state == Types.AgentState.WAIT) {
            if (event.priority.value < Types.Priority.EMERGENCY.value) {
                return Map.entry(false, "Role " + name + " is " + state.value);
            }
        }
        // System time events (source=time) bypass content salience filtering
        if ("time".equals(event.source)) {
            return Map.entry(true, "System time event: " + event.eventType
                    + " (tick=" + event.payload.get("tick") + ")");
        }
        // Layer 2: Salience — keyword-based relevance per role
        double relevance = 0.25;  // base
        String payloadText = String.valueOf(event.payload).toLowerCase();
        String eventText = (event.eventType.toLowerCase() + " " + payloadText);

        if (!interestKeywords.isEmpty()) {
            int hits = 0;
            for (String kw : interestKeywords) {
                if (eventText.contains(kw)) {
                    hits++;
                }
            }
            relevance += Math.min(0.60, 0.25 * hits);
        }
        // Bonus for matching skills (partial match)
        String skillText = String.join(" ", skills).toLowerCase();
        for (String word : eventText.split("\\s+")) {
            if (skillText.contains(word)) {
                relevance += 0.10;
                break;
            }
        }
        // Urgency bonus
        if (eventText.contains("urgent") || eventText.contains("critical")) {
            relevance += 0.15;
        }
        relevance = Math.min(1.0, relevance);
        double score = event.priority.value / 10.0 * 0.4 + relevance * 0.6;
        if (score < salienceThreshold) {
            return Map.entry(false, String.format("Salience %.2f < threshold %.2f (relevance=%.2f)",
                    score, salienceThreshold, relevance));
        }
        // Layer 3: PASS
        return Map.entry(true, String.format("PASS (score=%.2f, relevance=%.2f)", score, relevance));
    }

    /** Convert a passed event into a task for this role's queue. */
    public Task eventToTask(Types.Event event) {
        Urgency urgency = switch (event.priority) {
            case LOW -> Urgency.LOW;
            case HIGH -> Urgency.HIGH;
            case EMERGENCY -> Urgency.CRITICAL;
            default -> Urgency.NORMAL;
        };
        Object title = event.payload.get("title");
        String desc = "[" + event.source + "/" + event.eventType + "] "
                + (title != null ? title : truncate(String.valueOf(event.payload), 100));
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("event_id", event.id);
        ctx.put("payload", event.payload);
        return new Task(urgency.value, desc, event.source, ctx);
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    // ── Persona ────────────────────────────────────────────

    /** Build the role's full System Prompt. */
    public String buildSystemPrompt() {
        List<String> parts = new ArrayList<>();
        parts.add("You are " + name + ", your title is " + title
                + ", working as the " + roleId + " role.");
        parts.add("Personality: " + personality + ".");
        if (!skills.isEmpty()) {
            parts.add("Skills: " + String.join(", ", skills) + ".");
        }
        parts.add("Today is day " + timeManager().dayNumber() + ".");
        parts.add("If you currently have no task, you may directly rest. "
                + "Also note: do not send messages to others when you should not be disturbing them; "
                + "only send when necessary. So when you have no task, do not ask others anything, "
                + "just rest. You will be notified automatically when something comes up. "
                + "After you finish a task, report the completion to the colleague who assigned it, then rest.");
        parts.add("If you have a task that involves communicating with someone, make sure to do it "
                + "at the scheduled time — not early, not late — because the other party expects you "
                + "to contact them at that time.");
        parts.add("The company cloud drive is at /mnt/drive (every computer mounts the same shared folder):\n"
                + "  - /mnt/drive/Public — public shared directory, readable and writable by all employees (put shared resources, announcements, and collaboration files here)\n"
                + "  - /mnt/drive/" + username + " — your personal directory; only you can write to it; other employees have read-only access\n"
                + "  - Other employees' personal directories are read-only for you as well\n"
                + "Use the computer's file commands directly for file operations (ls / cat / cp / mv / rm, etc.); "
                + "to share a file with a colleague: write it to Public, or send the cloud drive file path via the talk attachment parameter.");
        parts.add("The company uses Git to manage project code (multi-person collaboration, multiple projects):\n"
                + "  - Each project is one repository; code is kept in its own repository per project\n"
                + "  - Run git commands on your personal computer (git clone / branch / add / commit / push / merge, etc.)\n"
                + "  - After completing a feature: first git pull to get the latest code, commit (with a clear description of what and why), "
                + "then push to merge into the main branch or open a merge request\n"
                + "  - When collaborating with others on the same project, sync the latest code first (git pull) to avoid conflicts; "
                + "when a conflict occurs, communicate with the relevant colleagues before merging\n"
                + "  - The main branch must always remain usable; do not force-overwrite others' code without permission\n"
                + "For changes that need collaboration with colleagues, discuss the division of work first, then commit and merge.");
        parts.add("Company email: every employee has a company mailbox (e.g. name@company.com), "
                + "and employees communicate via email (send_email to send / read_mail to receive).");
        if (group != null && !group.isEmpty()) {
            parts.add("You belong to the " + group + ", and your company email is " + mailAddress() + ". "
                    + "Colleague communication rules: the talk tool can only message members of your own group (quick in-group communication); "
                    + "communication with colleagues in other groups (other teams, release management, leadership, etc.) must use email "
                    + "(send_email to send, read_mail to check the inbox), for example reporting review results to the "
                    + "release management role Fang Jinyan, or collaborating with colleagues in other teams.");
        }
        if (systemPromptExtra != null && !systemPromptExtra.isEmpty()) {
            parts.add(systemPromptExtra);
        }
        // Inject yesterday's summary (if any) — only summaries strictly before today (day) are injected
        String summary = getLatestSummary(timeManager().dayNumber());
        if (summary != null && !summary.isEmpty()) {
            parts.add("\n[Yesterday's Summary]\n" + summary + "\n(The above is yesterday's summary, for you to continue your work.)");
        }
        return String.join("\n", parts);
    }

    // ── Queue operations (thread-safe) ─────────────────────

    /** Add a task to this role's priority queue (thread-safe). */
    public void addTask(Task task) {
        task.assignedRole = roleId;
        lock.lock();
        try {
            queue.add(task);
            logger.info("[{}] Task queued: {} (urgency={}, queue_depth={})",
                    roleId, task.taskId, Urgency.from(task.urgency).name(), queue.size());
        } finally {
            lock.unlock();
        }
        journal("Task received [" + Urgency.from(task.urgency).name() + "]: " + truncate(task.description, 120));
    }

    /** Pop the highest-priority task; returns null if the queue is empty. */
    public Task popTask() {
        lock.lock();
        try {
            return queue.poll();
        } finally {
            lock.unlock();
        }
    }

    /** Peek at the urgency of the next task (without removing it). */
    public Urgency peekNextUrgency() {
        lock.lock();
        try {
            Task t = queue.peek();
            return t == null ? null : Urgency.from(t.urgency);
        } finally {
            lock.unlock();
        }
    }

    public int queueDepth() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public Task currentTask() {
        return currentTask;
    }

    public boolean isBusy() {
        return currentTask != null;
    }

    // ── Owning AgentSystem (per-system collaboration objects) ────

    /**
     * Bind the owning AgentSystem: lazy dependencies such as computer/mail/notes/todos/journal/chat
     * resolve to this system's instances (not process-level global singletons), so multiple AgentSystems can coexist safely.
     * Injected by {@code AgentSystem.addRoles} / {@code RolePool.setupRole}; idempotent.
     */
    public void bindSystem(AgentSystem system) {
        this.system = system;
    }

    /** The owning AgentSystem (null for standalone roles not added to a pool; dependencies then fall back to global defaults). */
    public AgentSystem system() {
        return system;
    }

    /** This role's computer registry: system instance when bound, otherwise the process-level default singleton. */
    public ComputerManager computerManager() {
        return system != null ? system.computerManager : ComputerManager.getInstance();
    }

    /** This role's company mail service: system instance when bound, otherwise the process-level default singleton. */
    public MailService mailService() {
        return system != null ? system.mailService : MailService.getMailService();
    }

    /** This role's client-communication mutex: system instance when bound, otherwise the process-level default singleton. */
    public ClientCommunicationLock clientLock() {
        return system != null ? system.clientLock : ClientCommunicationLock.getInstance();
    }

    /** This role's MCP tool manager: system instance when bound, otherwise the process-level default singleton. */
    public MCPManager mcpManager() {
        return system != null ? system.mcpManager : Toolkits.getMcpManager();
    }

    /** This role's skill library manager: system instance when bound, otherwise the process-level default singleton. */
    public SkillManager skillManager() {
        return system != null ? system.skillManager : Toolkits.getSkillManager();
    }

    /** This role's chat message store (data source for the Web UI); null for standalone roles not bound to a system. */
    public ChatStore chatStore() {
        return system != null ? system.chatStore : null;
    }

    // ── Personal computer (per-role) ───────────────────────

    /** Get this role's personal computer (lazily created; automatically created and powered on when the role is added). */
    public Computer computer() {
        if (computer == null) {
            Map<String, Object> kwargs = new LinkedHashMap<>(computerKwargs);
            kwargs.put("username", username);
            kwargs.put("uid", uid);
            computer = computerManager().create(
                    computerKind, roleId, name, true, kwargs);
            if (!computer.isOn()) {
                computer.powerOn();
            }
        }
        return computer;
    }

    // ── Note store (per-role file storage) ─────────────────

    /** Get this role's note store instance (lazily initialized, isolated by role_id, under this system's data dir). */
    public NoteStore noteStore() {
        if (noteStore == null) {
            noteStore = new NoteStore(system != null ? system.notesDir().toString() : null,
                    roleId, timeManager());
        }
        return noteStore;
    }

    /** Read this role's most recent daily summary (used for the next day's cold-start prompt). */
    public String getLatestSummary(Integer beforeDay) {
        return noteStore().getLatestSummary(beforeDay);
    }

    /** Get this role's todo list store (lazily initialized, under this system's data dir). */
    public TodoStore todoStore() {
        if (todoStore == null) {
            todoStore = new TodoStore(roleId, system != null
                    ? system.todosDir().resolve(NoteStore.sanitizeTitle(roleId) + ".json").toString()
                    : null);
        }
        return todoStore;
    }

    // ── Activity journal ─────────────────────────────────────

    /**
     * Write to the role activity journal (data/journals/&lt;role_id&gt;.md).
     * Line format: [D&lt;day&gt; T&lt;tick&gt; HH:MM:SS] content.
     */
    public void journal(String entry) {
        String line = String.join(" ", String.valueOf(entry).trim().split("\\s+"));
        int day;
        int tick;
        try {
            day = timeManager().dayNumber();
            tick = timeManager().currentTick();
        } catch (Exception e) {
            day = 1;
            tick = 0;
        }
        String ts = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        // Journal dir: this system's data dir when bound, otherwise the process-level static default (legacy behavior)
        Path journalDir = system != null ? system.journalDir() : JOURNAL_DIR;
        try {
            synchronized (JOURNAL_LOCK) {
                Path path = journalDir.resolve(NoteStore.sanitizeTitle(
                        roleId == null || roleId.isEmpty() ? "shared" : roleId) + ".md");
                Files.createDirectories(path.getParent());
                Files.writeString(path, "[D" + day + " T" + tick + " " + ts + "] " + line + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            logger.debug("[{}] journal: {}", roleId, truncate(line, 100));
        } catch (Exception e) {
            logger.warn("[{}] failed to write journal: {}", roleId, truncate(line, 100));
        }
    }

    // ── Web trace recording (chain of thought / tool calls / final output) ──

    /** Max length of a chain-of-thought / note snippet kept in the Web trace feed. */
    public static final int TRACE_REASON_MAX = 4000;
    /** Max length of a tool's argument JSON kept in the Web trace feed. */
    public static final int TRACE_ARGS_MAX = 2000;
    /** Max length of a tool result kept in the Web trace feed. */
    public static final int TRACE_RESULT_MAX = 4000;
    /** Max length of a final-output message kept in the Web trace feed. */
    public static final int TRACE_ANSWER_MAX = 8000;

    /**
     * Record one entry into the Web trace feed (the system's {@link ChatStore}, when this role is
     * bound to an AgentSystem). Structured metadata (task id, tool round, arguments, result…)
     * rides in the message's {@code extra} field. Standalone roles (no system / no ChatStore)
     * simply skip recording, mirroring {@link #journal}.
     */
    public void recordTrace(String kind, String text, Map<String, Object> extra) {
        ChatStore store = system != null ? system.chatStore : null;
        if (store == null) {
            return;
        }
        if (text == null || text.isEmpty()) {
            text = "";
        }
        store.record(kind, group == null ? "" : group, roleId, name, "", "", text, null, extra);
    }

    /** Record a chain-of-thought entry (LLM {@code reasoning_content}). */
    public void recordReasoning(String reasoning, String taskId, Integer round) {
        if (reasoning == null || reasoning.isBlank()) {
            return;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("round", round);
        if (taskId != null && !taskId.isEmpty()) {
            extra.put("taskId", taskId);
        }
        recordTrace(ChatStore.KIND_REASON, truncate(reasoning.trim(), TRACE_REASON_MAX), extra);
    }

    /** Record assistant narration produced in the middle of a tool-calling round (content accompanying tool calls). */
    public void recordNote(String content, String taskId, Integer round) {
        if (content == null || content.isBlank()) {
            return;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("round", round);
        if (taskId != null && !taskId.isEmpty()) {
            extra.put("taskId", taskId);
        }
        recordTrace(ChatStore.KIND_NOTE, truncate(content.trim(), TRACE_REASON_MAX), extra);
    }

    /** Record one tool invocation: tool name + arguments (pretty JSON) + result. */
    public void recordToolCall(String toolName, String argsJson, String result,
                               String taskId, Integer round) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("tool", toolName == null ? "" : toolName);
        extra.put("args", argsJson == null ? "" : truncate(argsJson, TRACE_ARGS_MAX));
        extra.put("result", result == null ? "" : truncate(result, TRACE_RESULT_MAX));
        extra.put("round", round);
        if (taskId != null && !taskId.isEmpty()) {
            extra.put("taskId", taskId);
        }
        // text is intentionally empty for tool messages; the structured fields drive the UI card
        recordTrace(ChatStore.KIND_TOOL, "", extra);
    }

    /** Record a task's final output (final LLM reply / result). {@code status} is done / failed. */
    public void recordAnswer(String text, String taskId, String status, Integer tokens) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        if (status != null && !status.isEmpty()) {
            extra.put("status", status);
        }
        if (tokens != null) {
            extra.put("tokens", tokens);
        }
        if (taskId != null && !taskId.isEmpty()) {
            extra.put("taskId", taskId);
        }
        recordTrace(ChatStore.KIND_ANSWER, truncate(text.trim(), TRACE_ANSWER_MAX), extra);
    }

    // ── Time manager (work schedule) ───────────────────────

    /**
     * Get this role's work-schedule time manager.
     * Roles inside a system get this system's TimeEventBus via {@code AgentSystem.addRoles};
     * Standalone roles not explicitly bound fall back to the process-level default shared clock (legacy behavior, for standalone pools/demos).
     */
    public TimeEventBus timeManager() {
        if (timeManager == null) {
            timeManager = TimeEventBus.getDefaultBus();
        }
        return timeManager;
    }

    /** Bind a shared TimeEventBus (all roles share the same time source). */
    public void bindTimeManager(TimeEventBus tm) {
        this.timeManager = tm;
    }

    // ── MCP & Python Tool Management ────────────────────────

    /**
     * Import template-style toolkits (toolkits.*, Tool/Toolkit).
     * Each Tool is registered as a native tool: the flat parameter descriptions are converted by Tool.getInputSchema()
     * into an OpenAI-style input_schema for LLM calls.
     * Template toolkits have their dependencies injected at construction (role/store/manager); no extra binding is needed.
     */
    public int addToolkit(Toolkit toolkit) {
        if (tools == null) {
            tools = new ToolRegistry();
        }
        return tools.addToolkit(toolkit);
    }

    public List<String> mcpToolNames() {
        return tools == null ? new ArrayList<>() : tools.toolNames();
    }

    // ── Inter-role Communication (talk) ────────────────────

    /** Automatically register the talk toolkit. Called at RolePool.start(). */
    public void registerTalkTool() {
        if (pool == null) {
            return;
        }
        if (mcpToolNames().contains("talk")) {
            return;  // already registered
        }
        int added = addToolkit(new Talk(this, pool));
        logger.info("[{}] talk toolkit loaded — {} tools", roleId, added);
    }

    /** Programmatic inter-role communication (non-LLM path). */
    public String talkTo(String target, String message, String urgency) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("target", target);
        args.put("message", message);
        args.put("urgency", urgency);
        ToolRegistry.CallToolResult r = tools.callTool("talk", args);
        return r.content.isEmpty() ? "" : r.content.get(0).text;
    }

    // ── talk wait=true synchronous reply waiting ────────────

    /** Enter WAIT state: record the previous state and mark that we are waiting for a talk reply from target_id. */
    public void beginWait(String targetId) {
        waitingReplyFrom = targetId;
        stateBeforeWait = state;
        replyBox = null;  // clear any previous reply
        state = Types.AgentState.WAIT;
        journal("Entered WAIT, waiting for " + targetId + "'s reply");
    }

    /** Block until a reply arrives (infinite wait by default). Returns the reply, or null on timeout. */
    public String waitForReply(Long timeoutMillis) {
        replyCondLock.lock();
        try {
            if (replyBox != null) {
                return replyBox;
            }
            if (timeoutMillis != null) {
                try {
                    replyCond.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                try {
                    replyCond.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return replyBox;
        } finally {
            replyCondLock.unlock();
        }
    }

    /** End WAIT: clear the waiting state and restore the state from before WAIT. */
    public void endWait() {
        waitingReplyFrom = null;
        replyBox = null;
        if (stateBeforeWait != null && state == Types.AgentState.WAIT) {
            state = stateBeforeWait;
        }
        stateBeforeWait = null;
        journal("WAIT ended, state restored");
    }

    /** Deliver a talk reply to a waiting role in WAIT (wakes its blocked worker thread). */
    public void deliverReply(String content) {
        replyCondLock.lock();
        try {
            replyBox = content;
            replyCond.signalAll();
        } finally {
            replyCondLock.unlock();
        }
    }

    /**
     * Public WAIT protocol: block until the target role's talk reply arrives.
     * Enter WAIT before delivering the task — if the task were delivered first, an instant reply
     * would land before begin_wait clears the mailbox, losing the reply (race condition).
     */
    public String talkWait(String targetId, Task task, Long timeoutMillis) {
        beginWait(targetId);
        try {
            if (task != null) {
                AgentRole target = null;
                if (pool != null) {
                    target = pool.getRoleOrNull(targetId);
                }
                if (target == null) {
                    throw new IllegalArgumentException(
                            "talk_wait: target role " + targetId + " is not in the role pool, cannot deliver task");
                }
                target.addTask(task);
            }
            return waitForReply(timeoutMillis);
        } finally {
            endWait();
        }
    }

    // ── Public accessors (for the tool layer / state-store layer) ─

    /** Read-only access to the personal computer (null if not created; no lazy creation). */
    public Computer computerIfCreated() {
        return computer;
    }

    /** Read-only wait chain: whose talk reply this role is waiting for. */
    public String waitingReplyFrom() {
        return waitingReplyFrom;
    }

    /** Test helper: current reply mailbox content (null if no reply received). */
    public String debugReplyBox() {
        return replyBox;
    }

    /** Read-only access to the owning role pool. */
    public RolePool pool() {
        return pool;
    }

    /** Locked snapshot of the pending queue. */
    public List<Task> pendingTasks() {
        lock.lock();
        try {
            return new ArrayList<>(queue);
        } finally {
            lock.unlock();
        }
    }

    /** Snapshot of recent task history (limit truncates to the most recent N entries). */
    public List<Task> taskHistory(Integer limit) {
        if (limit == null) {
            return new ArrayList<>(taskHistory);
        }
        int size = taskHistory.size();
        int from = Math.max(0, size - limit);
        return new ArrayList<>(taskHistory.subList(from, size));
    }

    /** Replace the task history wholesale (used by StateStore restore). */
    public void restoreTaskHistory(List<Task> tasks) {
        taskHistory.clear();
        taskHistory.addAll(tasks);
    }

    /** Bind a personal computer object (used when StateStore restores container bindings). */
    public void bindComputer(Computer comp) {
        this.computer = comp;
    }

    /** Ensure the tool registry is initialized. */
    public ToolRegistry ensureTools() {
        if (tools == null) {
            tools = new ToolRegistry();
        }
        return tools;
    }

    /** Dynamically register a single tool on the role. */
    public void addSingleTool(String name, String description, Map<String, Object> inputSchema,
                              ToolRegistry.ToolHandler handler, String source) {
        ensureTools().addTool(name, description, inputSchema, handler, source);
    }

    /** Dynamically remove a single tool from the role. Returns whether it existed. */
    public boolean removeSingleTool(String name) {
        if (tools == null) {
            return false;
        }
        boolean existed = tools.toolNames().contains(name);
        tools.removeTool(name);
        return existed;
    }

    // ── Tool-calling LLM execution ─────────────────────────

    /**
     * Execute the task with the tool-calling loop (native function calling).
     *
     * @return {finalText, totalTokens}.
     */
    public Map.Entry<String, Integer> executeWithTools(Task task) {
        if (llm == null) {
            throw new IllegalStateException("LLM not initialized for role " + roleId);
        }
        String system = buildSystemPrompt();
        List<Map<String, Object>> openaiTools = new ArrayList<>();
        if (tools != null) {
            openaiTools = tools.toOpenaiTools();
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", system));
        messages.add(msg("user", task.description));

        int totalTokens = 0;
        int roundNo = 0;
        while (true) {
            roundNo++;
            /*if (roundNo > MAX_TOOL_ROUNDS) {
                throw new ToolLoopError("Tool calls exceeded " + MAX_TOOL_ROUNDS + " rounds without converging "
                        + "(total " + totalTokens + " tokens), task failed");
            }*/
            LLM.ToolsResponse response = llm.chatWithTools(messages, openaiTools, 0.7, null);
            totalTokens += response.totalTokens();
            /*if (MAX_TOOL_TOTAL_TOKENS != null && totalTokens > MAX_TOOL_TOTAL_TOKENS) {
                throw new ToolLoopError("Tool calls consumed " + totalTokens + " tokens, exceeding the limit of "
                        + MAX_TOOL_TOTAL_TOKENS + ", task failed");
            }*/
            List<Map<String, Object>> toolCalls = response.toolCalls;
            // Web trace: record the chain of thought of this round (reasoning_content), if the backend produced one
            recordReasoning(response.reasoning, task.taskId, roundNo);
            String roundContent = response.content == null ? "" : response.content;
            if (toolCalls.isEmpty()) {
                // LLM call failed (API timeout/exception): must not be treated as a success
                if (roundContent.startsWith(LLM.LLM_ERROR_MARKERS)) {
                    throw new ToolLoopError("LLM call failed (round " + roundNo + "): "
                            + truncate(roundContent, 120));
                }
                // Web trace: the round contains no tool calls → content is the model's plain reply;
                // the task-level final output is recorded by RolePool.roleLoop after the task completes
                logger.debug("[{}] tool loop: final answer received in round {} (no tool calls), task done", roleId, roundNo);
                return Map.entry(roundContent, totalTokens);
            }
            // Web trace: narration the model emits while it is still about to call tools
            recordNote(roundContent, task.taskId, roundNo);
            // Append this round's LLM reply (including native tool_calls) to the conversation history
            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", roundContent.isEmpty() ? null : roundContent);
            assistantMsg.put("tool_calls", toolCalls);
            messages.add(assistantMsg);
            logger.debug("[{}] tool loop: appended LLM output message ({} chars, {} native tool calls)",
                    roleId, roundContent.length(), toolCalls.size());

            // Execute this round's tool calls sequentially
            for (Map<String, Object> call : toolCalls) {
                Object fnObj = call.get("function");
                Map<String, Object> fn = fnObj instanceof Map
                        ? (Map<String, Object>) fnObj : new LinkedHashMap<>();
                String toolName = Json.str(fn, "name", "");
                String callId = Json.str(call, "id", "");
                Map<String, Object> toolArgs;
                String toolArgsRaw = Json.str(fn, "arguments", "{}");
                try {
                    Object raw = Json.parse(toolArgsRaw);
                    toolArgs = raw instanceof Map ? (Map<String, Object>) raw : new LinkedHashMap<>();
                } catch (Exception e) {
                    toolArgs = new LinkedHashMap<>();
                    logger.warn("[{}] arguments of tool {} are not valid JSON: {}",
                            roleId, toolName, toolArgsRaw);
                }
                String toolResult;
                if (tools == null) {
                    toolResult = "Error: no tools available (tool '" + toolName + "' not found)";
                } else {
                    ToolRegistry.CallToolResult result = tools.callTool(toolName, toolArgs);
                    toolResult = result.content.isEmpty() ? String.valueOf(result) : result.content.get(0).text;
                }
                logger.info("[{}] Tool call: {}({}) → {}", roleId, toolName,
                        Json.stringify(toolArgs), truncate(toolResult, 80));
                journal("Called tool " + toolName + "(" + truncate(Json.stringify(toolArgs), 80)
                        + ") → " + truncate(toolResult == null ? "" : toolResult, 100));
                // Web trace: record the tool invocation together with its arguments and result
                recordToolCall(toolName, Json.stringifyPretty(toolArgs), toolResult, task.taskId, roundNo);
                // Native protocol: tool results are fed back as role:"tool" messages, linked by tool_call_id
                messages.add(msgWithToolCallId(toolName, callId, toolResult));
            }
            logger.debug("[{}] tool loop: round {} still contains tool calls, continuing (max {} rounds)",
                    roleId, roundNo, MAX_TOOL_ROUNDS);
        }
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> msgWithToolCallId(String toolName, String callId, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", callId);
        m.put("content", content);
        return m;
    }

    // ── Internal accessors (used by RolePool) ────────────────

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public LLM llm() {
        return llm;
    }

    public void setLlm(LLM llm) {
        this.llm = llm;
    }

    public void setPool(RolePool pool) {
        this.pool = pool;
    }

    public void setCurrentTask(Task task) {
        this.currentTask = task;
    }

    public ToolRegistry tools() {
        return tools;
    }

    public void appendTaskHistory(Task task) {
        taskHistory.add(task);
    }

    public void setState(Types.AgentState state) {
        this.state = state;
    }
}
