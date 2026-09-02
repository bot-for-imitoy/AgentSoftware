package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.event.TimeEventBus;
import com.agent.software.llm.LLM;
import com.agent.software.llm.OpenAICompatLLM;
import com.agent.software.tools.Toolkit;
import com.agent.software.tools.Toolkits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Role pool (RolePool) — the RolePool from the Python version of roles.py.
 *
 * Manages all roles running concurrently: one resident daemon worker thread per role, looping:
 * 1. Pop the highest-priority task → 2. Execute with an OpenAI-compatible LLM → 3. Trigger callback → 4. Repeat.
 */
public class RolePool {

    private static final Logger logger = LoggerFactory.getLogger(RolePool.class);

    private final Map<String, AgentRole> roles = new LinkedHashMap<>();
    private int uidCounter = 0;                              // in-container uid allocation counter (1100 + registration sequence)
    private final ExecutorService executor;
    private final Map<String, Future<?>> futures = new LinkedHashMap<>();
    private final AtomicBoolean shutdownFlag = new AtomicBoolean(false);

    private String llmApiKey;
    private String llmModel;
    private TimeEventBus timeManager;                        // shared time source (injected by AgentSystem)
    private boolean autoToolkits = true;                     // default toolkit auto-wiring switch
    private final AgentSystem owner;                         // owning AgentSystem (null = standalone role pool)

    public RolePool(String llmApiKey, String llmModel,
                    TimeEventBus timeManager, boolean autoToolkits) {
        this(llmApiKey, llmModel, timeManager, autoToolkits, null);
    }

    /**
     * Construct with an owning AgentSystem: role setup/resignation/LLM configuration all go
     * through that system's dedicated collaboration objects (computer registry/email/MCP/
     * skills/conversation lock/data directory), allowing multiple AgentSystems to coexist
     * safely in the same process. When owner is null, falls back to the process-wide global
     * defaults (standalone role pool, legacy behavior).
     */
    public RolePool(String llmApiKey, String llmModel,
                    TimeEventBus timeManager, boolean autoToolkits,
                    AgentSystem owner) {
        this.llmApiKey = llmApiKey;
        this.llmModel = llmModel;
        this.timeManager = timeManager;
        this.autoToolkits = autoToolkits;
        this.owner = owner;
        // One resident worker per role: uses Java 21+ virtual threads — no longer bound by the
        // fixed thread pool max_workers cap (a full 46-role team need not worry about platform
        // thread counts), role workers block mostly on LLM HTTP waits/queue polling, and
        // virtual threads have minimal overhead.
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("role-", 0).factory());
    }

    public RolePool() {
        this(null, null, null, true);
    }

    // ── Role management ────────────────────────────────────

    /** Register a role. Must be called before start(). */
    public void addRole(AgentRole role) {
        if (roles.containsKey(role.roleId)) {
            throw new IllegalArgumentException("Role '" + role.roleId + "' already exists");
        }
        roles.put(role.roleId, role);
        // in-container uid allocation: 1100 + registration sequence (stable registration order → stable uid across restarts)
        if (role.uid <= 1100) {
            uidCounter++;
            role.uid = 1100 + uidCounter;
        }
        // every role registered into the pool immediately gets its own activity journal
        role.journal("Role ready: " + role.name + " — " + (role.title.isEmpty() ? role.roleId : role.title));
    }

    /**
     * Role setup (single entry point): bind the shared clock and the owning system → default
     * tools → default MCP groups. Idempotent.
     */
    public void setupRole(AgentRole role) {
        // bind the shared time source: must precede addToolkit(time)
        if (timeManager != null) {
            role.bindTimeManager(timeManager);
        }
        // bind the owning system: computer/email/notes/todos/journal dependencies all go through this system instance
        if (owner != null) {
            role.bindSystem(owner);
        }
        // default tools (memory/note/time/todo/task_view/pc/mcp_manager/skill_manager/email)
        for (Toolkit toolkit : Toolkits.defaultToolkits(role)) {
            role.addToolkit(toolkit);
        }
        // default MCP tool groups (e.g. file_ops file operations, installed onto the role's personal computer)
        for (String group : Toolkits.DEFAULT_MCP_GROUPS) {
            role.mcpManager().installGroupDefaults(role, group);
        }
    }

    /** Create an LLM client per role (with role log prefix); configuration goes through unified OpenAI layered resolution. */
    public LLM newLlm(String roleId) {
        return new OpenAICompatLLM(llmApiKey, null, llmModel, roleId,
                owner != null ? owner.configStore : null);
    }

    /** Dynamic onboarding: register a new role and immediately start its worker thread (used by the hiring flow). */
    public AgentRole addRoleAndStart(AgentRole role) {
        if (roles.containsKey(role.roleId)) {
            throw new IllegalArgumentException("Role '" + role.roleId + "' already exists");
        }
        roles.put(role.roleId, role);
        if (autoToolkits) {
            setupRole(role);
        }
        startOneRole(role);
        role.journal("Role ready: " + role.name + " — " + (role.title.isEmpty() ? role.roleId : role.title));
        return role;
    }

    /** Start a single role worker (shared by start / addRoleAndStart). */
    public void startOneRole(AgentRole role) {
        role.setRunning(true);
        role.setPool(this);  // back-reference for talk tool
        if (role.llm() == null) {
            role.setLlm(newLlm(role.roleId));
        }
        role.registerTalkTool();
        Future<?> fut = executor.submit(() -> roleLoop(role));
        futures.put(role.roleId, fut);
        logger.info("Role '{}' worker started", role.roleId);
    }

    public AgentRole getRole(String name) {
        AgentRole role = roles.get(name);
        if (role == null) {
            throw new IllegalArgumentException("Role '" + name + "' not found. Available: " + roles.keySet());
        }
        return role;
    }

    /** Get a role by name (returns null if not present). */
    public AgentRole getRoleOrNull(String name) {
        return roles.get(name);
    }

    /** Look up a role by person name (used by the talk tool); falls back to role_id lookup. */
    public AgentRole getRoleByName(String name) {
        for (AgentRole r : roles.values()) {
            if (r.name.equals(name)) {
                return r;
            }
        }
        return roles.get(name);
    }

    /** Resignation: remove the role and shut down its personal computer. Returns whether the removal succeeded. */
    public boolean removeRole(String roleId) {
        if (!roles.containsKey(roleId)) {
            return false;
        }
        AgentRole role = roles.remove(roleId);
        role.setRunning(false);
        futures.remove(roleId);
        // resignation: destroy the personal computer (via this system's computer registry, does not affect same-named roles in other systems)
        try {
            role.computerManager().destroy(roleId);
        } catch (Exception e) {
            logger.warn("[{}] Failed to destroy computer on resignation", roleId, e);
        }
        logger.info("Role '{}' removed (resignation)", roleId);
        return true;
    }

    /** Return the list of all roles (in registration order). */
    public List<AgentRole> allRoles() {
        return new ArrayList<>(roles.values());
    }

    public List<String> listRoles() {
        return new ArrayList<>(roles.keySet());
    }

    /** Global notification: write one entry to every role's activity journal. */
    public void journalAll(String entry) {
        for (AgentRole role : roles.values()) {
            role.journal(entry);
        }
    }

    // ── Lifecycle ──────────────────────────────────────────

    /** Start all role worker threads. */
    public void start() {
        for (AgentRole role : roles.values()) {
            if (autoToolkits) {
                setupRole(role);
            }
            startOneRole(role);
        }
    }

    /** Gracefully stop all role workers. */
    public void shutdown(boolean wait) {
        logger.info("Shutting down RolePool...");
        shutdownFlag.set(true);
        for (AgentRole role : roles.values()) {
            role.setRunning(false);
        }
        executor.shutdown();
        if (wait) {
            try {
                executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        logger.info("RolePool shut down");
    }

    // ── Task assignment ────────────────────────────────────

    /** Route a task to the specified role's queue. */
    public void assignTask(String roleName, AgentRole.Task task) {
        getRole(roleName).addTask(task);
    }

    /** Snapshot of all role statuses. */
    public Map<String, Map<String, Object>> getStatus() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<String, AgentRole> e : roles.entrySet()) {
            AgentRole role = e.getValue();
            AgentRole.Urgency nextU = role.peekNextUrgency();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("busy", role.isBusy());
            m.put("queue_depth", role.queueDepth());
            m.put("current_task", role.currentTask() != null ? role.currentTask().description : null);
            m.put("next_urgency", nextU != null ? nextU.name() : null);
            result.put(e.getKey(), m);
        }
        return result;
    }

    // ── Internal: role worker loop ─────────────────────────

    /** Main loop of a single role worker thread. */
    public void roleLoop(AgentRole role) {
        logger.info("[{}] Worker loop started", role.roleId);
        while (role.isRunning() && !shutdownFlag.get()) {
            AgentRole.Task task = role.popTask();
            if (task == null) {
                try {
                    Thread.sleep(100);  // idle polling
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                continue;
            }
            role.setCurrentTask(task);
            task.status = AgentRole.STATUS_RUNNING;
            logger.info("[{}] Processing task: {} ({})", role.roleId, task.taskId,
                    task.description.length() > 60 ? task.description.substring(0, 60) : task.description);
            role.journal("Starting task: " + truncate(task.description, 120));

            if (role.onTaskStart != null) {
                try {
                    role.onTaskStart.call(role, task);
                } catch (Exception e) {
                    logger.error("[{}] on_task_start callback failed", role.roleId, e);
                }
            }
            try {
                LLM llm = role.llm();
                if (llm == null) {
                    throw new IllegalStateException("LLM not initialized for role");
                }
                String resultText;
                int tokens;
                if (role.tools() != null && role.tools().toolCount() > 0) {
                    // Tool-calling loop: LLM can invoke MCP tools
                    Map.Entry<String, Integer> r = role.executeWithTools(task);
                    resultText = r.getKey();
                    tokens = r.getValue();
                } else {
                    // Simple chat: no tools available
                    LLM.ChatResponse resp = llm.chat(role.buildSystemPrompt(), task.description, 0.7, 512);
                    resultText = resp.text;
                    tokens = resp.tokens;
                    if (resultText.startsWith(LLM.LLM_ERROR_MARKERS)) {
                        throw new AgentRole.ToolLoopError("LLM call failed: " + truncate(resultText, 120));
                    }
                }
                task.result = resultText;
                task.tokensConsumed = tokens;
                task.status = AgentRole.STATUS_DONE;
                logger.info("[{}] Task {} done ({} tokens): {}", role.roleId, task.taskId, tokens,
                        truncate(resultText, 80));
                role.journal("Task completed (" + tokens + " tokens): " + truncate(resultText, 150));
                role.appendTaskHistory(task);
            } catch (Exception exc) {
                task.result = "[ERROR] " + exc;
                task.status = AgentRole.STATUS_FAILED;
                logger.error("[{}] Task {} failed: {}", role.roleId, task.taskId, exc.toString());
                role.journal("Task failed: " + exc);
                role.appendTaskHistory(task);
            }
            role.setCurrentTask(null);

            if (role.onTaskDone != null) {
                try {
                    role.onTaskDone.call(role, task);
                } catch (Exception e) {
                    logger.error("[{}] on_task_done callback failed", role.roleId, e);
                }
            }
        }
        logger.info("[{}] Worker loop exited", role.roleId);
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }

    // ── Accessors ─────────────────────────────────────────────

    public TimeEventBus timeManager() {
        return timeManager;
    }

    /** The AgentSystem this pool belongs to (null for a standalone role pool). */
    public AgentSystem owner() {
        return owner;
    }
}
