package com.agent.software;

import com.agent.software.client.Client;
import com.agent.software.client.ClientChannel;
import com.agent.software.computers.ComputerManager;
import com.agent.software.event.Event;
import com.agent.software.event.EventBus;
import com.agent.software.event.EventType;
import com.agent.software.event.Priority;
import com.agent.software.event.TimeBus;
import com.agent.software.io.Input;
import com.agent.software.io.StdInput;
import com.agent.software.role.CompanyRoster;
import com.agent.software.role.Employee;
import com.agent.software.role.Role;
import com.agent.software.role.RolePool;
import com.agent.software.role.RoleState;
import com.agent.software.role.Staffing;
import com.agent.software.services.MailConfig;
import com.agent.software.services.MailMessage;
import com.agent.software.services.MailService;
import com.agent.software.store.ConfigStore;
import com.agent.software.store.JsonStore;
import com.agent.software.store.PathManager;
import com.agent.software.store.RoleTemplateStore;
import com.agent.software.store.ToolkitConfig;
import com.agent.software.tools.toolkits.mcp.MCPManager;
import com.agent.software.tools.toolkits.skill.SkillManager;
import com.agent.software.web.ChatStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 组合根：持有全部协作者，注入给 Role，并把时间/事件/班次接起来。
 *
 * <p>对外只有 12 个 getter + start/stop/pause/resume；闸门与班次逻辑都是 private。
 */
public class AgentSystem {

    private static final Logger logger = LoggerFactory.getLogger(AgentSystem.class);

    private final Path dataDir;
    private final TimeBus timeBus;
    private final EventBus eventBus;
    private final RolePool rolePool;
    private final ComputerManager computerManager;
    private final CompanyRoster roster;
    private final Staffing staffing;
    private final MailService mailService;
    private final ClientChannel clientChannel;
    private final ChatStore chatStore;
    private final MCPManager mcpManager;
    private final SkillManager skillManager;
    private final Input input;
    private final ConfigStore configStore;
    private final ToolkitConfig toolkitConfig;

    private volatile boolean onDuty = false;
    /** 已经因为"没有后续任务"自动暂停过，避免重复触发。 */
    private volatile boolean autoPaused = false;
    /** 已通知过的 (messageId|收件人)，防止同一封邮件重复唤醒。 */
    private final java.util.Set<String> notifiedMail = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public AgentSystem() {
        this(null, new StdInput());
    }

    public AgentSystem(Path dataDir, Input input) {
        this.dataDir = dataDir == null ? Paths.get("data") : dataDir;
        this.timeBus = new TimeBus();
        this.eventBus = new EventBus();
        this.rolePool = new RolePool(this);
        this.computerManager = new ComputerManager();
        this.chatStore = new ChatStore();
        this.mcpManager = new MCPManager();
        this.skillManager = new SkillManager(this.dataDir.resolve("skills").toString());
        this.configStore = new ConfigStore(resolveConfigFile(this.dataDir));
        this.input = input == null ? new StdInput() : input;
        this.mailService = MailService.create(MailConfig.fromEnv(), this.dataDir);
        this.clientChannel = new ClientChannel(
                new Client("CLIENT", "Client A", mailService.getClientAddress()), chatStore);
        this.roster = new CompanyRoster();
        loadRoster();
        this.toolkitConfig = loadToolkitConfig();
        this.staffing = new Staffing(this);

        // 时间 → 事件 → 角色 的接线
        eventBus.bind(this, timeBus);
        timeBus.addTickListener(tb -> eventBus.tick(tb.now()));
        timeBus.addTickListener(this::onTick);
        timeBus.setIdleChecker(this::canFastForward);
        timeBus.setNextStopProvider(() -> {
            Event next = eventBus.nextDue();
            return next == null ? null : next.targetTime;
        });
        timeBus.setRolloverHook(() -> logger.info("Clock rolled over: {}", timeBus.currentDateTime()));
        // 模拟时间倍率：系统属性 agentsoftware.timeScale 优先，其次 AGENTSOFTWARE_TIME_SCALE，默认 1.0（实时）
        double timeScale = 1.0;
        try {
            String raw = System.getProperty("agentsoftware.timeScale",
                    System.getenv().getOrDefault("AGENTSOFTWARE_TIME_SCALE", "1.0"));
            timeScale = Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
        }
        timeBus.setTimeScale(timeScale);
        logger.info("Simulation time scale: {}x (1 tick = 1 simulated second)", timeBus.getTimeScale());
        mailService.setDeliveryListener(this::onMailDelivered);

        // 默认大组 = 管理组
        for (Employee e : roster.defaultCohort()) {
            try {
                staffing.draftIn(e.roleId);
            } catch (Exception ex) {
                logger.error("failed to draft default cohort member {}", e.roleId, ex);
            }
        }
    }

    // ── 对外 getter ─────────────────────────────────────────────

    public TimeBus getTimeBus() {
        return timeBus;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public RolePool getRolePool() {
        return rolePool;
    }

    public ComputerManager getComputerManager() {
        return computerManager;
    }

    public CompanyRoster getRoster() {
        return roster;
    }

    public Staffing getStaffing() {
        return staffing;
    }

    public MailService getMailService() {
        return mailService;
    }

    public ClientChannel getClientChannel() {
        return clientChannel;
    }

    public ChatStore getChatStore() {
        return chatStore;
    }

    public MCPManager getMcpManager() {
        return mcpManager;
    }

    public SkillManager getSkillManager() {
        return skillManager;
    }

    public Input getInput() {
        return input;
    }

    public ConfigStore getConfigStore() {
        return configStore;
    }

    /** 默认工具集配置（v3 冻结 API 外的一个 getter，见报备清单）。 */
    public ToolkitConfig getToolkitConfig() {
        return toolkitConfig;
    }

    // ── 生命周期 ────────────────────────────────────────────────

    public void start() {
        rolePool.start();
        timeBus.start();
        logger.info("AgentSystem started: {}", timeBus.currentDateTime());
    }

    public void stop() {
        timeBus.stop();
        rolePool.stop();
        logger.info("AgentSystem stopped");
    }

    public void pause() {
        timeBus.pause();
        logger.warn("AgentSystem paused");
    }

    public void resume() {
        autoPaused = false;
        timeBus.resume();
        logger.info("AgentSystem resumed");
    }

    // ── 内部闸门 ────────────────────────────────────────────────

    /** 全员 IDLE（含"没有角色"视为不空闲）。 */
    private boolean allRolesIdle() {
        if (rolePool.isEmpty()) {
            return false;
        }
        for (Role r : rolePool.all()) {
            if (r.getState() != RoleState.IDLE) {
                return false;
            }
        }
        return true;
    }

    /**
     * 时间总线是否可以在空闲时快进：只有"确实还有后续工作可等"才快进。
     * 没有后续工作时不快进（改为按真实时间走一格），由 {@link #onTick} 立即 pause。
     */
    private boolean canFastForward() {
        return allRolesIdle() && hasFutureWork();
    }

    /**
     * 是否还有后续工作：排期事件、下班暂存事件、或角色队列里的待处理项。
     * 三个都要看 —— 事件一旦投递就从排期表移除，只看排期会因为"已投递但还没被 worker 取走"而误判。
     */
    private boolean hasFutureWork() {
        if (eventBus.nextDue() != null) {
            return true;
        }
        if (!eventBus.heldEvents().isEmpty()) {
            return true;
        }
        for (Role r : rolePool.all()) {
            if (r.queueDepth() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 班次切换：由时间线程直调角色（不走事件队列）。 */
    private void onTick(TimeBus tb) {
        boolean working = tb.isWorkingHours();
        if (working && !onDuty) {
            onDuty = true;
            for (Role r : rolePool.all()) {
                r.onShiftStart();
            }
            eventBus.post(shiftEvent(EventType.SHIFT_START, tb));
            eventBus.releaseHeld();
            logger.info("SHIFT_START at {}", tb.currentDateTime());
        } else if (!working && onDuty) {
            onDuty = false;
            for (Role r : rolePool.all()) {
                r.onShiftEnd();
            }
            eventBus.post(shiftEvent(EventType.SHIFT_END, tb));
            logger.info("SHIFT_END at {}", tb.currentDateTime());
        }

        // 后续没有任何任务：停止推进时钟，等外部 resume()
        if (allRolesIdle() && !hasFutureWork() && !autoPaused) {
            autoPaused = true;
            logger.info("No further tasks — auto-pausing at {}", tb.currentDateTime());
            pause();
        }
    }

    private static Event shiftEvent(EventType type, TimeBus tb) {
        return Event.builder()
                .type(type)
                .priority(Priority.HIGH)
                .at(tb.now())
                .content(type == EventType.SHIFT_START ? "Shift start" : "Shift end")
                .source("time")
                .build();
    }

    private void onMailDelivered(MailMessage message, String recipient) {
        if (message == null || recipient == null) {
            return;
        }
        if (recipient.equalsIgnoreCase(message.senderEmail)) {
            return;
        }
        Role target = findRoleByMailbox(recipient);
        if (target == null) {
            return;
        }
        // 同一封邮件对同一收件人只通知一次：to/cc 重叠、重复地址都不该产生第二条通知
        String dedupeKey = message.messageId + "|" + recipient.toLowerCase();
        if (!notifiedMail.add(dedupeKey)) {
            logger.debug("Suppressed duplicate mail notification: {}", dedupeKey);
            return;
        }
        Event e = Event.builder()
                .from(message.senderEmail)
                .to(target.roleId)
                .type(EventType.NEW_MAIL)
                .priority(Priority.NORMAL)
                .at(timeBus.now())
                .source("mail")
                .payload(Map.of("message_id", message.messageId,
                        "subject", message.subject == null ? "" : message.subject))
                .content("New mail from " + message.senderName + " <" + message.senderEmail
                        + ">, subject: \"" + message.subject + "\", message_id=" + message.messageId
                        + ". Call open_mail with this message_id; if it is already read, no action is needed.")
                .build();
        eventBus.post(e);
    }

    private Role findRoleByMailbox(String mailbox) {
        for (Role r : rolePool.all()) {
            if (mailService.getAddress(r.roleId).equalsIgnoreCase(mailbox)) {
                return r;
            }
        }
        return null;
    }

    // ── 加载 ────────────────────────────────────────────────────

    private void loadRoster() {
        Path templates = copyResourceIfMissing("/role_templates.json", dataDir.resolve("role_templates.json"));
        if (templates == null) {
            logger.warn("no role templates found; roster will be empty");
            return;
        }
        RoleTemplateStore store = new RoleTemplateStore(JsonStore.of(templates));
        for (Employee e : store.employees()) {
            roster.add(e);
        }
        logger.info("Loaded {} employee(s) from {}", roster.size(), templates);
    }

    private ToolkitConfig loadToolkitConfig() {
        Path cfg = copyResourceIfMissing("/toolkits.default.json", dataDir.resolve("toolkits.default.json"));
        return cfg == null ? null : new ToolkitConfig(JsonStore.of(cfg));
    }

    private static Path copyResourceIfMissing(String resource, Path target) {
        try {
            if (Files.exists(target)) {
                return target;
            }
            try (InputStream in = AgentSystem.class.getResourceAsStream(resource)) {
                if (in == null) {
                    return null;
                }
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.write(target, in.readAllBytes());
            }
            return target;
        } catch (IOException e) {
            return null;
        }
    }

    /** 数据根目录（内部使用）。 */
    Path dataDir() {
        return dataDir;
    }

    /**
     * 配置文件位置解析（API Key 等放这里）：
     * <ol>
     *   <li>优先 {@code $AGENTSOFTWARE_CONFIG_DIR/config.json} 或 {@code $XDG_CONFIG_HOME/AgentSoftware/config.json}
     *       （通常是 {@code ~/.config/AgentSoftware/config.json}），由 {@link PathManager} 解析；</li>
     *   <li>兼容旧位置 {@code <dataDir>/config.json}（仅当 XDG 那份不存在而它存在时）；</li>
     *   <li>都没有时返回 XDG 路径作为默认落点，启动日志会打印。</li>
     * </ol>
     */
    private static Path resolveConfigFile(Path dataDir) {
        Path xdg = PathManager.createDefault().configFile("config.json");
        if (Files.exists(xdg)) {
            logger.info("Config file: {}", xdg);
            return xdg;
        }
        Path local = dataDir.resolve("config.json");
        if (Files.exists(local)) {
            logger.info("Config file (legacy data dir): {}", local);
            return local;
        }
        logger.info("Config file not found; default location is {}", xdg);
        return xdg;
    }
}
