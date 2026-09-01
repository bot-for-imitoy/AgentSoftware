package com.agent.software.role;

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
import com.agent.software.tools.ToolkitBridge;
import com.agent.software.utils.Json;
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
 * 角色系统核心: AgentRole (单个角色) — Python 版 roles.py 的 Java 对应物.
 *
 * 每个角色拥有: 人格 (姓名/职位/职责/技能)、线程安全优先级任务队列、
 * 独立 LLM 会话 (角色专属 System Prompt)、独立 worker 线程.
 */
public class AgentRole {

    private static final Logger logger = LoggerFactory.getLogger(AgentRole.class);

    // ── 工具调用循环上限 ───────────────────────────────────────
    public static final int MAX_TOOL_ROUNDS = 20;               // 最多工具调用轮数
    public static final Integer MAX_TOOL_TOTAL_TOKENS = null;   // 单任务累计 Token 上限 (暂时放开)

    // ── 角色活动日志 ──────────────────────────────────────────
    public static Path JOURNAL_DIR = Paths.get("data/journals");
    private static final Object JOURNAL_LOCK = new Object();

    /** 工具调用循环超限或 LLM 调用失败. 任务应标记 failed. */
    public static class ToolLoopError extends RuntimeException {
        public ToolLoopError(String message) {
            super(message);
        }
    }

    /** 任务紧急度 — 数值越大越紧急, 先处理. */
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

    /** 任务状态: pending|running|done|failed. */
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    /** 角色队列中的任务. 按 urgency 降序弹出. */
    public static final class Task {
        public int urgency;                        // 正数紧急度 (队列按降序)
        public String taskId;
        public String description = "";
        public String source = "";
        public Map<String, Object> context = new LinkedHashMap<>();
        public double createdAt;
        public String status = STATUS_PENDING;
        public String result = "";
        public int tokensConsumed = 0;
        public String assignedRole = "";
        private final long seq;                    // 同 urgency 时按入队顺序稳定弹出

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

        /** Task → 可序列化 Map (urgency 存正数). */
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

    /** 任务完成回调 (on_task_start / on_task_done). */
    @FunctionalInterface
    public interface TaskCallback {
        void call(AgentRole role, Task task);
    }

    // ── AgentRole 字段 ──────────────────────────────────────

    public String name;                                  // 人名, e.g. "张三"
    public String roleId = "";                           // 功能角色, e.g. "coder"
    public String username = "";                         // 容器/系统用户名 (汉语拼音)
    public int uid = 0;                                  // 容器内 uid (1100+注册序号)
    public String title = "";
    public String responsibilities = "";
    public String personality = "";
    public List<String> skills = new ArrayList<>();
    public String systemPromptExtra = "";
    public boolean isDefault = false;
    public String group = "";                            // 所属分组 (talk 组内限制)
    public String email = "";                            // 显式公司邮箱 (可选)
    public String computerKind = "podman";
    public Map<String, Object> computerKwargs = new LinkedHashMap<>();

    // 事件过滤状态 (per-role)
    public Types.AgentState state = Types.AgentState.ON_DUTY_IDLE;
    public double salienceThreshold = 0.4;
    public Set<String> interestKeywords = new LinkedHashSet<>();

    // 内部状态 (由 RolePool 管理)
    private final PriorityQueue<Task> queue = new PriorityQueue<>(
            Comparator.comparingInt((Task t) -> -t.urgency).thenComparingLong(t -> t.seq));
    private final ReentrantLock lock = new ReentrantLock();
    private Task currentTask = null;
    private volatile boolean running = true;
    private LLM llm = null;                              // 惰性初始化
    private ToolRegistry tools = null;                   // 惰性初始化
    private RolePool pool = null;                        // talk 用回引
    private NoteStore noteStore = null;
    private TodoStore todoStore = null;
    private TimeEventBus timeManager = null;
    private Computer computer = null;

    // talk wait=true 同步等待回复状态
    private String waitingReplyFrom = null;
    private final ReentrantLock replyCondLock = new ReentrantLock();
    private final Condition replyCond = replyCondLock.newCondition();
    private String replyBox = null;
    private Types.AgentState stateBeforeWait = null;

    private final List<Task> taskHistory = new ArrayList<>();

    public TaskCallback onTaskStart = null;
    public TaskCallback onTaskDone = null;

    // ── 构造 ──────────────────────────────────────────────

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

    /** 便捷构造: 从构建器创建. */
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
     * 补齐派生字段: username (未显式指定时回退 role_id) 与 uid (容器内用户号).
     * 模板角色的拼音用户名由 role_templates.json 的 username 字段直接提供
     * (PinyinMap 已并入 JSON), 此处只处理程序化创建的角色.
     */
    private void postInit() {
        if (username == null || username.isEmpty()) {
            username = !roleId.isEmpty() ? roleId : "agent";
        }
        if (uid <= 0) {
            uid = 1100;  // 由 RolePool.addRole 注册时分配 (1100 + 注册序号)
        }
    }

    /** 该员工的公司邮箱地址 (每位成员都有邮箱). */
    public String mailAddress() {
        return MailService.getMailService().emailFor(this);
    }

    // ── Event Filter (per-role Layer 1-3) ──────────────────

    /**
     * 运行每角色 3 层过滤.
     *
     * @return {accepted, reason} 二元组.
     */
    public Map.Entry<Boolean, String> evaluateEvent(Types.Event event) {
        // Layer 1: State Mask (WAIT 与 OFF_DUTY 同等对待)
        if (state == Types.AgentState.OFF_DUTY || state == Types.AgentState.WRAPPING_UP
                || state == Types.AgentState.WAIT) {
            if (event.priority.value < Types.Priority.EMERGENCY.value) {
                return Map.entry(false, "Role " + name + " is " + state.value);
            }
        }
        // 系统时间事件 (source=time) 绕过内容显著性过滤
        if ("time".equals(event.source)) {
            return Map.entry(true, "系统时间事件: " + event.eventType
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
        if (eventText.contains("urgent") || eventText.contains("critical") || eventText.contains("紧急")) {
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

    /** 将已通过的事件转换为本角色队列的任务. */
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

    /** 构建角色完整 System Prompt. */
    public String buildSystemPrompt() {
        List<String> parts = new ArrayList<>();
        parts.add("你是 " + name + "，职位是 " + title + "，负责 " + roleId + " 工作。");
        parts.add("性格特点：" + personality + "。");
        if (!skills.isEmpty()) {
            parts.add("技能：" + String.join(", ", skills) + "。");
        }
        parts.add("今天是第 " + timeManager().dayNumber() + " 天。");
        parts.add("如果当前没有任务，你可以直接调用休息。"
                + "并且你需要注意：不要在不应该打扰其他人的时候向其他人发送信息，"
                + "只有必要时才会发送。因此当你没有任务的时候，不要询问其它人，"
                + "直接休息即可。当有事情的时候会自动提醒你的。"
                + "当你完成任务后，你需要向给你安排任务的同事汇报任务完成情况，然后再休息。");
        parts.add("如果你有与其他人沟通的任务，请务必保证在指定时间与其沟通，"
                + "不能提前也不能延后，因为其他人会认为你应该在此时与他沟通。");
        parts.add("公司云盘位于 /mnt/drive（每台电脑都挂载同一份共享文件夹）：\n"
                + "  - /mnt/drive/Public —— 公用共享目录，所有员工都可读写（公共资源、公告、协作文件放这里）\n"
                + "  - /mnt/drive/" + name + " —— 你的个人目录，只有你能写入；其他员工只读\n"
                + "  - 其他员工的个人目录你也只有只读权限\n"
                + "文件操作直接用电脑的文件命令（ls / cat / cp / mv / rm 等）；"
                + "分享文件给同事：写入 Public，或用 talk 的 attachment 参数发送云盘文件路径。");
        parts.add("公司使用 Git 管理项目代码（多人协作、多项目并存）：\n"
                + "  - 每个项目一个仓库，代码按项目放在各自的仓库里\n"
                + "  - 开发在你的个人电脑上执行 git 命令（git clone / branch / add / commit / push / merge 等）\n"
                + "  - 完成一个功能后：先 git pull 拉取最新代码，提交（commit 并写明改动内容和原因），"
                + "再 push 合入主干或发起合并请求\n"
                + "  - 多人在同一项目协作时，动手前先同步最新代码（git pull），避免冲突；"
                + "遇到冲突先与相关同事沟通再合并\n"
                + "  - 主干分支必须始终保持可用；不要私自强行覆盖别人的代码\n"
                + "需要与同事协作的改动，先沟通分工再提交、合并。");
        parts.add("公司邮箱：每位员工都有一个公司邮箱（如 name@company.com），"
                + "员工之间通过电子邮件交流（send_email 发送 / read_mail 收件）。");
        if (group != null && !group.isEmpty()) {
            parts.add("你属于「" + group + "」组，你的公司邮箱是 " + mailAddress() + "。"
                    + "同事沟通规则：talk 工具只能给同组成员发送消息（组内快速沟通）；"
                    + "跨组同事（其他小组、版本管理、领导等）沟通必须使用邮件 "
                    + "(send_email 发送、read_mail 查看收件箱)，例如向版本管理角色"
                    + "方谨言汇报审核结果、与其他小组的同事协作时请发邮件。");
        }
        if (systemPromptExtra != null && !systemPromptExtra.isEmpty()) {
            parts.add(systemPromptExtra);
        }
        // 注入昨日总结 (如果有) — 只注入严格早于今天(day)的总结
        String summary = getLatestSummary(timeManager().dayNumber());
        if (summary != null && !summary.isEmpty()) {
            parts.add("\n[昨日总结]\n" + summary + "\n(以上是昨天的总结, 供你延续工作.)");
        }
        return String.join("\n", parts);
    }

    // ── Queue operations (thread-safe) ─────────────────────

    /** 向本角色优先级队列添加任务 (线程安全). */
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
        journal("收到任务 [" + Urgency.from(task.urgency).name() + "]: " + truncate(task.description, 120));
    }

    /** 弹出最高优先级任务; 队列为空返回 null. */
    public Task popTask() {
        lock.lock();
        try {
            return queue.poll();
        } finally {
            lock.unlock();
        }
    }

    /** 查看下一个任务的紧急度 (不移除). */
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

    // ── Personal computer (per-role) ───────────────────────

    /** 获取该角色的个人电脑 (惰性创建, 角色添加时自动创建并开机). */
    public Computer computer() {
        if (computer == null) {
            Map<String, Object> kwargs = new LinkedHashMap<>(computerKwargs);
            kwargs.put("username", username);
            kwargs.put("uid", uid);
            computer = ComputerManager.getInstance().create(
                    computerKind, roleId, name, true, kwargs);
            if (!computer.isOn()) {
                computer.powerOn();
            }
        }
        return computer;
    }

    // ── Note store (per-role file storage) ─────────────────

    /** 获取该角色的笔记存储实例 (惰性初始化, 按 role_id 隔离). */
    public NoteStore noteStore() {
        if (noteStore == null) {
            noteStore = new NoteStore(null, roleId, timeManager());
        }
        return noteStore;
    }

    /** 读取该角色最近一次的每日总结 (用于下一天冷启动提示词). */
    public String getLatestSummary(Integer beforeDay) {
        return noteStore().getLatestSummary(beforeDay);
    }

    /** 获取该角色的 Todo 清单存储 (惰性初始化). */
    public TodoStore todoStore() {
        if (todoStore == null) {
            todoStore = new TodoStore(roleId, null);
        }
        return todoStore;
    }

    // ── 活动日志 (journal) ───────────────────────────────

    /**
     * 写入角色活动日志 (data/journals/&lt;role_id&gt;.md).
     * 行格式: [D&lt;第几天&gt; T&lt;Tick&gt; HH:MM:SS] 内容.
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
        try {
            synchronized (JOURNAL_LOCK) {
                Path path = JOURNAL_DIR.resolve(NoteStore.sanitizeTitle(
                        roleId == null || roleId.isEmpty() ? "shared" : roleId) + ".md");
                Files.createDirectories(path.getParent());
                Files.writeString(path, "[D" + day + " T" + tick + " " + ts + "] " + line + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            logger.debug("[{}] 活动日志: {}", roleId, truncate(line, 100));
        } catch (Exception e) {
            logger.warn("[{}] 写活动日志失败: {}", roleId, truncate(line, 100));
        }
    }

    // ── Time manager (作息时间) ───────────────────────────

    /** 获取该角色的作息时间管理器 (未显式绑定时返回进程级默认共享时钟). */
    public TimeEventBus timeManager() {
        if (timeManager == null) {
            timeManager = TimeEventBus.getDefaultBus();
        }
        return timeManager;
    }

    /** 绑定共享 TimeEventBus (所有角色共用同一个时间源). */
    public void bindTimeManager(TimeEventBus tm) {
        this.timeManager = tm;
    }

    // ── MCP & Python Tool Management ────────────────────────

    /** 导入整个工具类. 返回新增工具数 (跳过重复). */
    public int addToolkit(ToolRegistry.ToolKit toolkit) {
        if (tools == null) {
            tools = new ToolRegistry();
        }
        return tools.addToolkit(toolkit);
    }

    /**
     * 导入模板风格工具类 (toolkits.*, Tool/Toolkit). 桥接为旧版 ToolKit 后加载.
     * 模板工具类在构造时已注入依赖 (角色/存储/管理器), 无需走旧版 binder 绑定.
     */
    public int addToolkit(Toolkit toolkit) {
        if (tools == null) {
            tools = new ToolRegistry();
        }
        return tools.addToolkit(ToolkitBridge.toLegacy(toolkit));
    }

    public List<String> mcpToolNames() {
        return tools == null ? new ArrayList<>() : tools.toolNames();
    }

    // ── Inter-role Communication (talk) ────────────────────

    /** 自动注册 talk 工具类. 在 RolePool.start() 时调用. */
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

    /** 编程式角色间通信 (non-LLM path). */
    public String talkTo(String target, String message, String urgency) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("target", target);
        args.put("message", message);
        args.put("urgency", urgency);
        ToolRegistry.CallToolResult r = tools.callTool("talk", args);
        return r.content.isEmpty() ? "" : r.content.get(0).text;
    }

    // ── talk wait=true 同步等待回复 ───────────────────────

    /** 进入 WAIT 状态: 记录原状态, 标记在等 target_id 的 talk 回复. */
    public void beginWait(String targetId) {
        waitingReplyFrom = targetId;
        stateBeforeWait = state;
        replyBox = null;  // 清空历史回复
        state = Types.AgentState.WAIT;
        journal("进入 WAIT, 等待 " + targetId + " 回复");
    }

    /** 阻塞等待回复 (默认无限等待). 返回回复内容, 超时未收到返回 null. */
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

    /** 结束 WAIT: 清空等待状态, 恢复进入 WAIT 前的状态. */
    public void endWait() {
        waitingReplyFrom = null;
        replyBox = null;
        if (stateBeforeWait != null && state == Types.AgentState.WAIT) {
            state = stateBeforeWait;
        }
        stateBeforeWait = null;
        journal("WAIT 结束, 状态已恢复");
    }

    /** 投递 talk 回复给处于 WAIT 的等待者 (唤醒其阻塞的 worker 线程). */
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
     * 公开 WAIT 协议: 阻塞等待目标角色的 talk 回复.
     * 先进入 WAIT 再投递任务 — 若先投递, 对方秒回时回复会落在 begin_wait 的
     * 信箱清空之前, 导致回复丢失 (竞态).
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
                            "talk_wait: 目标角色 " + targetId + " 不在角色池中, 无法投递任务");
                }
                target.addTask(task);
            }
            return waitForReply(timeoutMillis);
        } finally {
            endWait();
        }
    }

    // ── 公开访问器 (供工具层/存档层使用) ──────────────────

    /** 只读获取个人电脑 (未创建返回 null, 不惰性创建). */
    public Computer computerIfCreated() {
        return computer;
    }

    /** 只读等待链: 该角色正在等待谁的 talk 回复. */
    public String waitingReplyFrom() {
        return waitingReplyFrom;
    }

    /** 测试辅助: 当前回复信箱内容 (未收到回复为 null). */
    public String debugReplyBox() {
        return replyBox;
    }

    /** 只读所在角色池. */
    public RolePool pool() {
        return pool;
    }

    /** 待处理队列的加锁快照. */
    public List<Task> pendingTasks() {
        lock.lock();
        try {
            return new ArrayList<>(queue);
        } finally {
            lock.unlock();
        }
    }

    /** 最近任务历史快照 (limit 截取最近 N 条). */
    public List<Task> taskHistory(Integer limit) {
        if (limit == null) {
            return new ArrayList<>(taskHistory);
        }
        int size = taskHistory.size();
        int from = Math.max(0, size - limit);
        return new ArrayList<>(taskHistory.subList(from, size));
    }

    /** 整体替换任务历史 (StateStore 恢复用). */
    public void restoreTaskHistory(List<Task> tasks) {
        taskHistory.clear();
        taskHistory.addAll(tasks);
    }

    /** 绑定个人电脑对象 (StateStore 恢复容器绑定时用). */
    public void bindComputer(Computer comp) {
        this.computer = comp;
    }

    /** 确保工具注册表已初始化. */
    public ToolRegistry ensureTools() {
        if (tools == null) {
            tools = new ToolRegistry();
        }
        return tools;
    }

    /** 动态注册单个工具到角色. */
    public void addSingleTool(String name, String description, Map<String, Object> inputSchema,
                              ToolRegistry.ToolHandler handler, String source) {
        ensureTools().addTool(name, description, inputSchema, handler, source);
    }

    /** 动态移除角色上的单个工具. 返回是否存在. */
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
     * 用工具调用循环执行任务 (原生 function calling).
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
                throw new ToolLoopError("工具调用超过 " + MAX_TOOL_ROUNDS + " 轮仍未收敛 "
                        + "(累计 " + totalTokens + " tokens), 任务失败");
            }*/
            LLM.ToolsResponse response = llm.chatWithTools(messages, openaiTools, 0.7, null);
            totalTokens += response.totalTokens();
            /*if (MAX_TOOL_TOTAL_TOKENS != null && totalTokens > MAX_TOOL_TOTAL_TOKENS) {
                throw new ToolLoopError("工具调用累计 " + totalTokens + " tokens 超过上限 "
                        + MAX_TOOL_TOTAL_TOKENS + ", 任务失败");
            }*/
            List<Map<String, Object>> toolCalls = response.toolCalls;
            if (toolCalls.isEmpty()) {
                // LLM 调用失败 (API 超时/异常): 不能当作成功结果
                if (response.content.startsWith(LLM.LLM_ERROR_MARKERS)) {
                    throw new ToolLoopError("LLM 调用失败 (第 " + roundNo + " 轮): "
                            + truncate(response.content, 120));
                }
                logger.debug("[{}] 工具循环: 第 {} 轮收到终答 (无工具调用), 任务完成", roleId, roundNo);
                return Map.entry(response.content, totalTokens);
            }
            // 把本轮 LLM 回复 (含原生 tool_calls) 追加进对话历史
            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", response.content.isEmpty() ? null : response.content);
            assistantMsg.put("tool_calls", toolCalls);
            messages.add(assistantMsg);
            logger.debug("[{}] 工具循环: 追加 LLM 输出消息 ({} 字符, {} 个原生工具调用)",
                    roleId, response.content.length(), toolCalls.size());

            // 顺序执行本轮的每个工具调用
            for (Map<String, Object> call : toolCalls) {
                Object fnObj = call.get("function");
                Map<String, Object> fn = fnObj instanceof Map
                        ? (Map<String, Object>) fnObj : new LinkedHashMap<>();
                String toolName = Json.str(fn, "name", "");
                String callId = Json.str(call, "id", "");
                Map<String, Object> toolArgs;
                try {
                    Object raw = Json.parse(Json.str(fn, "arguments", "{}"));
                    toolArgs = raw instanceof Map ? (Map<String, Object>) raw : new LinkedHashMap<>();
                } catch (Exception e) {
                    toolArgs = new LinkedHashMap<>();
                    logger.warn("[{}] 工具 {} 的 arguments 不是合法 JSON: {}",
                            roleId, toolName, fn.get("arguments"));
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
                journal("调用工具 " + toolName + "(" + truncate(Json.stringify(toolArgs), 80)
                        + ") → " + truncate(toolResult == null ? "" : toolResult, 100));
                // 原生协议: 工具结果以 role:"tool" 消息回喂, 关联 tool_call_id
                messages.add(msgWithToolCallId(toolName, callId, toolResult));
            }
            logger.debug("[{}] 工具循环: 第 {} 轮仍包含工具调用, 继续下一轮 (上限 {} 轮)",
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

    // ── 内部访问器 (RolePool 使用) ─────────────────────────

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
