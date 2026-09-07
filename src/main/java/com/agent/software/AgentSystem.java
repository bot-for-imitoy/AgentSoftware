package com.agent.software;

import com.agent.software.computers.Computer;
import com.agent.software.computers.ComputerManager;
import com.agent.software.conversation.ConversationManager;
import com.agent.software.core.Types;
import com.agent.software.event.EventDispatcher;
import com.agent.software.event.TimeEventBus;
import com.agent.software.io.Input;
import com.agent.software.io.StdInput;
import com.agent.software.io.WebInput;
import com.agent.software.role.AgentRole;
import com.agent.software.role.RoleLoader;
import com.agent.software.role.RolePool;
import com.agent.software.services.MailService;
import com.agent.software.store.ConfigStore;
import com.agent.software.tools.toolkits.client.ClientCommunicationLock;
import com.agent.software.tools.toolkits.mcp.MCPManager;
import com.agent.software.tools.toolkits.skill.SkillManager;
import com.agent.software.web.ChatStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * System management class (AgentSystem) - centrally manages TimeEventBus + RolePool + event dispatch
 * (Python version agent_system.py).
 *
 * <p><b>Self-contained design</b>: Each {@code AgentSystem} directly owns its own set of collaboration objects
 * (clock / config / computer registry / mailbox / MCP and skill managers / client communication lock / chat storage)
 * and a data root directory, without depending on process-level global singletons. A system can therefore run
 * independently, and multiple systems can be created in the same process without interfering with each other
 * (see docs/agent-system-multi-instance.md).
 */
public class AgentSystem {

    private static final Logger logger = LoggerFactory.getLogger(AgentSystem.class);

    public final TimeEventBus timeManager;   // shared time source
    public final RolePool pool;
    public final EventDispatcher dispatcher;
    public final boolean autoToolkits;
    public final ConfigStore configStore;

    // ── Per-system independent collaboration objects (multiple instances do not interfere) ────────────────────
    /** This system's role computer registry (role_id → Computer). */
    public final ComputerManager computerManager;
    /** This system's company mailbox (data stored under dataDir/mail). */
    public final MailService mailService;
    /** This system's MCP tool manager. */
    public final MCPManager mcpManager;
    /** This system's skill library manager (data stored under dataDir/skills). */
    public final SkillManager skillManager;
    /** This system's mutex lock for communicating with Client A. */
    public final ClientCommunicationLock clientLock;
    /** This system's chat message storage + Client A conversation coordination (Web UI data source). */
    public final ChatStore chatStore;
    /** This system's role ↔ LLM API conversation registry (one ongoing dialogue per role, per day). */
    public final ConversationManager conversationManager = new ConversationManager();

    /**
     * This system's client-input channel (console {@link StdInput} or Web-page {@link WebInput}),
     * used by talk_to_client to read the client's reply.
     */
    public final Input input;

    /** This system's data root directory (default ./data); all persisted files live under it. */
    private final Path dataDir;

    /** Constructor with the default data directory (./data); behavior is consistent with historical versions. */
    public AgentSystem(List<AgentRole> roles, List<String> roleIds,
                       double checkInterval, boolean autoToolkits,
                       Input input) {
        this(Paths.get("data"), roles, roleIds, checkInterval, autoToolkits, input);
    }

    /**
     * Constructor with an explicit data directory: each {@code AgentSystem}'s persisted files (logs/notes/todos/
     * mail/state/skills) all live under its own dataDir. Multiple systems can safely coexist by passing different
     * directories.
     */
    public AgentSystem(Path dataDir, List<AgentRole> roles, List<String> roleIds,
                       double checkInterval, boolean autoToolkits,
                       Input input) {
        this.dataDir = dataDir != null ? dataDir : Paths.get("data");
        this.timeManager = new TimeEventBus();
        this.timeManager.checkInterval = checkInterval;
        this.configStore = new ConfigStore();
        this.computerManager = new ComputerManager();
        this.mailService = new MailService(null, this.dataDir.resolve("mail").toString());
        this.mcpManager = new MCPManager();
        this.skillManager = new SkillManager(this.dataDir.resolve("skills").toString());
        this.clientLock = new ClientCommunicationLock();
        this.chatStore = new ChatStore();
        this.pool = new RolePool(null, null, timeManager, autoToolkits, this);
        this.dispatcher = new EventDispatcher(pool);
        // New-mail notification: mailbox delivery in this system is turned into a targeted NEW_MAIL event
        // for the receiving role, guiding it to call read_mail (wired here, after dispatcher creation)
        this.mailService.setDeliveryListener(this::notifyNewMail);
        this.autoToolkits = autoToolkits;
        this.input = input;
        // Web input reads from this system's chat store and identifies the waiting member through this
        // system's client-communication lock (bound here because both are created inside this constructor)
        if (input instanceof WebInput webInput) {
            webInput.bind(this.chatStore, this.clientLock);
        }

        // Time thread events → event dispatcher (unified entry for schedule events)
        this.timeManager.setEventSender(this::onTimeEvent);
        // Clock control: busy flow while anyone works, fast-forward when everyone is idle,
        // and an explicit gate that only rolls to the next day's 08:00 after the whole team
        // has finished its daily wrap-up (all roles OFF_DUTY).
        this.timeManager.setIdleChecker(this::allRolesIdle);
        this.timeManager.setBusyChecker(() -> !allRolesIdle());
        this.timeManager.setRolloverReadyChecker(this::dayRolloverReady);
        this.timeManager.setRolloverForceHook(this::forceWrapUp);
        // Busy-clock speed is tunable per run (simulated minutes per real second of team work)
        double simRate = TimeEventBus.DEFAULT_SIM_MINUTES_PER_REAL_SECOND;
        try {
            simRate = Double.parseDouble(System.getProperty(
                    "agentsoftware.simMinutesPerRealSecond",
                    System.getenv().getOrDefault("AGENTSOFTWARE_SIM_MINUTES_PER_REAL_SECOND",
                            String.valueOf(TimeEventBus.DEFAULT_SIM_MINUTES_PER_REAL_SECOND))));
        } catch (NumberFormatException ignored) {
        }
        this.timeManager.simMinutesPerRealSecond = simRate;

        List<AgentRole> all = new ArrayList<>();
        if (roles != null) {
            all.addAll(roles);
        }
        if (roleIds != null) {
            for (String rid : roleIds) {
                all.add(RoleLoader.getTemplate(rid));
            }
        }
        if (!all.isEmpty()) {
            addRoles(all);
        }
    }

    public AgentSystem() {
        this(null, null, 30.0, true, new StdInput());
    }

    // ── Data directories (all rooted at dataDir) ────────────────────────

    public Path dataDir() {
        return dataDir;
    }

    /** Role activity journal directory. */
    public Path journalDir() {
        return dataDir.resolve("journals");
    }

    /** Role notes / daily summary directory. */
    public Path notesDir() {
        return dataDir.resolve("notes");
    }

    /** Role todo list directory. */
    public Path todosDir() {
        return dataDir.resolve("todos");
    }

    /** Company mailbox data directory. */
    public Path mailDir() {
        return dataDir.resolve("mail");
    }

    /** Role personal computer directory (for local simulation). */
    public Path computersDir() {
        return dataDir.resolve("computers");
    }

    /** Enterprise cloud drive mount directory. */
    public Path driveDir() {
        return dataDir.resolve("drive");
    }

    /** Skill library directory. */
    public Path skillsDir() {
        return dataDir.resolve("skills");
    }

    /** Full state snapshot file. */
    public Path stateFile() {
        return dataDir.resolve("state.json");
    }

    // ── Role management ──────────────────────────────────────────

    /** Register roles in batch: the time-consuming setup (computer creation + MCP server startup) runs in parallel across threads. */
    public List<AgentRole> addRoles(List<AgentRole> roles) {
        // First uniformly bind the shared time source and this system reference (fast, serial) - ensuring that all of
        // the role's lazy dependencies (computer/mailbox/notes/todos/journal/chat) live in this system, not process-level global singletons
        for (AgentRole role : roles) {
            role.bindTimeManager(timeManager);
            role.bindSystem(this);
        }
        if (autoToolkits) {
            // Parallel setup: one virtual thread per role (Java 21+), with a semaphore limiting concurrency,
            // to avoid saturating podman/npx (the max_workers semantics of the original fixed thread pool are unchanged)
            int maxWorkers = Math.min(10, roles.size());
            if (maxWorkers < 1) {
                maxWorkers = 1;
            }
            java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(maxWorkers);
            ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
            for (AgentRole role : roles) {
                ex.submit(() -> {
                    try {
                        gate.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        pool.setupRole(role);
                    } catch (Exception e) {
                        logger.error("AgentSystem: role {} setup failed (computer/MCP)", role.roleId, e);
                    } finally {
                        gate.release();
                    }
                });
            }
            ex.shutdown();
            try {
                ex.awaitTermination(10, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Register in order (including journal initialization)
        for (AgentRole role : roles) {
            pool.addRole(role);
            logger.info("AgentSystem: role registered {} ({})", role.roleId, role.name);
        }
        return roles;
    }

    /** Register a single role. */
    public AgentRole addRole(AgentRole role) {
        return addRoles(List.of(role)).get(0);
    }

    /** Register all default management roles (CEO/COO/HR/CFO). */
    public List<AgentRole> addDefaultRoles() {
        List<AgentRole> roles = new ArrayList<>();
        for (String rid : RoleLoader.DEFAULT_ROLES) {
            roles.add(addRole(RoleLoader.getTemplate(rid)));
        }
        return roles;
    }

    public AgentRole getRole(String roleId) {
        return pool.getRole(roleId);
    }

    public Map<String, Map<String, Object>> getStatus() {
        return pool.getStatus();
    }

    // ── Events and tasks ────────────────────────────────────────

    /** Unified entry point for schedule events from the time thread. */
    public void onTimeEvent(Types.Event event) {
        if (TimeEventBus.EVENT_SHIFT_START.equals(event.eventType)) {
            for (AgentRole role : pool.allRoles()) {
                // Wake up at shift start; WAIT roles are already on duty waiting for replies, so do not reset them
                if (role.state != Types.AgentState.ON_DUTY_IDLE
                        && role.state != Types.AgentState.WAIT) {
                    role.setState(Types.AgentState.ON_DUTY_IDLE);
                    logger.info("AgentSystem: SHIFT_START → {} is on duty (ON_DUTY_IDLE)", role.roleId);
                }
                // Auto power-on at shift start
                try {
                    Computer comp = role.computerIfCreated();
                    if (comp != null && !comp.isOn()) {
                        comp.powerOn();
                        logger.info("AgentSystem: SHIFT_START → {} computer auto powered on", role.roleId);
                    }
                } catch (Exception e) {
                    logger.error("AgentSystem: {} failed to power on at shift start", role.roleId, e);
                }
            }
            pool.journalAll("Global notice: shift start (SHIFT_START) at " + timeManager.currentDateTime());
        } else if (TimeEventBus.EVENT_SHIFT_END.equals(event.eventType)) {
            pool.journalAll("Global notice: shift end (SHIFT_END) at " + timeManager.currentDateTime()
                    + ", each role summarizes and then rests");
            // Shift-end unstick: roles synchronously waiting for a talk reply would otherwise block
            // forever on their current task (their counterpart is going off duty) and never reach the
            // queued summary task — wake them so the daily wrap-up can complete.
            for (AgentRole role : pool.allRoles()) {
                if (role.isWaiting()) {
                    role.abortWait("[System: the shift ended at " + timeManager.shiftEndTime()
                            + " and the colleague you were waiting for has gone off duty. "
                            + "Treat this as their reply for now, finish up your current task, "
                            + "then call the summary tool to wrap up today's work.]");
                }
            }
        }
        dispatcher.trigger(event);
    }

    /**
     * Whether all roles are idle for the clock (used by fast-forward and the busy clock). Before the
     * shift ends, any busy role or any queued task counts as activity. Once the shift has ended
     * (18:00) the daily wrap-up is the only remaining activity: tasks still queued by then are
     * intentionally held (off-duty roles do not start new ordinary work) and must NOT block the
     * rollover — only roles that are still busy (running their summary / an emergency) keep the
     * clock waiting. An empty role pool is treated as not idle.
     */
    public boolean allRolesIdle() {
        List<AgentRole> roles = pool.allRoles();
        if (roles.isEmpty()) {
            return false;
        }
        boolean afterShiftEnd = timeManager.tickOfDay() >= timeManager.shiftEndTick;
        for (AgentRole r : roles) {
            if (r.isBusy()) {
                return false;
            }
            if (!afterShiftEnd && r.queueDepth() > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Day-rollover gate (queried by the time thread): the clock may jump to the next day's 08:00
     * shift start only once every role has finished its daily wrap-up and is OFF_DUTY. Held leftover
     * tasks in off-duty queues are fine — they run on the next shift.
     */
    public boolean dayRolloverReady() {
        List<AgentRole> roles = pool.allRoles();
        if (roles.isEmpty()) {
            return false;
        }
        for (AgentRole r : roles) {
            if (r.state != Types.AgentState.OFF_DUTY) {
                return false;
            }
        }
        return true;
    }

    /**
     * Wrap-up force hook (invoked by the time thread when the shift ended but the team did not
     * finish wrapping up within the grace period). Roles still synchronously waiting for a talk
     * reply are woken first (their counterpart has gone off duty and will not answer until the
     * next shift), then every idle non-OFF_DUTY role is marked OFF_DUTY so the day loop can roll
     * over instead of deadlocking (e.g. a role whose summary task failed).
     */
    public void forceWrapUp() {
        logger.warn("AgentSystem: wrap-up grace exceeded — forcing remaining roles OFF_DUTY");
        for (AgentRole r : pool.allRoles()) {
            if (r.isWaiting()) {
                r.abortWait("[System: the daily wrap-up deadline passed — treat this as the reply for now, "
                        + "finish up and rest; leftover work resumes at the next 08:00 shift.]");
            }
        }
        for (AgentRole r : pool.allRoles()) {
            if (r.state != Types.AgentState.OFF_DUTY && !r.isBusy()) {
                r.setState(Types.AgentState.OFF_DUTY);
                r.journal("Forced OFF_DUTY by the time manager (daily wrap-up grace exceeded, summary missing)");
            }
        }
    }

    /** Post an event to the event bus, broadcasting it to all roles. */
    public Map<String, Map<String, Object>> trigger(Types.Event event) {
        return dispatcher.trigger(event);
    }

    // ── Mail delivery → "new mail" notification ─────────────────────────

    /** Event type of the targeted per-recipient notification sent when a mail lands in an employee's inbox. */
    public static final String EVENT_NEW_MAIL = "NEW_MAIL";

    /**
     * {@link MailService.MailDeliveryListener} registered on this system's MailService: after a mail is
     * delivered to a mailbox that belongs to one of this system's roles, dispatch a targeted
     * {@value #EVENT_NEW_MAIL} event (source "email", NORMAL priority) to that role so its next task guides
     * it to view the mail via read_mail.
     *
     * <p>Delivering through the {@link EventDispatcher} keeps the system's notification discipline: a role
     * that is on duty accepts the event and gets a queued task; a role that is off duty / wrapping up /
     * synchronously waiting is not disturbed (skipped with a journal record) and reads the mail when it
     * checks its inbox later. External addresses and the sender's own mailbox are not notified.
     */
    private void notifyNewMail(MailService.MailMessage message, String recipientMailbox) {
        if (message == null || recipientMailbox == null || recipientMailbox.isEmpty()) {
            return;
        }
        if (recipientMailbox.equalsIgnoreCase(message.senderEmail)) {
            return;  // sending to yourself needs no notification
        }
        AgentRole recipient = findRoleByMailbox(recipientMailbox);
        if (recipient == null) {
            return;  // not an internal employee mailbox (e.g. an external recipient)
        }
        String senderDesc = (message.senderName == null || message.senderName.isEmpty())
                ? message.senderEmail
                : message.senderName + " <" + message.senderEmail + ">";
        String subject = message.subject == null ? "" : message.subject;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "You have a new email from " + senderDesc
                + " (subject: \"" + subject + "\"). Please call read_mail to view it in your inbox.");
        payload.put("sender_email", message.senderEmail);
        payload.put("sender_name", message.senderName);
        payload.put("subject", subject);
        payload.put("message_id", message.messageId);
        dispatcher.trigger(new Types.Event("email", EVENT_NEW_MAIL,
                Types.Priority.NORMAL, payload, recipient.roleId));
    }

    /** Find the role whose company mailbox equals the given address, or null when it is not an employee mailbox. */
    private AgentRole findRoleByMailbox(String mailbox) {
        for (AgentRole role : pool.allRoles()) {
            if (mailService.emailFor(role).equalsIgnoreCase(mailbox)) {
                return role;
            }
        }
        return null;
    }

    /** Directly assign a task to the specified role. */
    public void assignTask(String roleId, AgentRole.Task task) {
        pool.assignTask(roleId, task);
    }

    // ── Lifecycle ──────────────────────────────────────────

    /** Start the system: role pool threads + time thread. Startup moment = Tick 0 / Day 1. */
    public void start() {
        pool.start();
        timeManager.start();
        logger.info("AgentSystem started: {}", describe());
    }

    /** Stop the system: time thread + role pool. */
    public void stop() {
        timeManager.stop();
        pool.shutdown(false);
        logger.info("AgentSystem stopped");
    }

    // ── Time queries (forward to the shared TimeEventBus) ───────────────────

    public int tick() {
        return timeManager.currentTick();
    }

    public int day() {
        return timeManager.dayNumber();
    }

    public String describe() {
        return timeManager.describe();
    }
}
