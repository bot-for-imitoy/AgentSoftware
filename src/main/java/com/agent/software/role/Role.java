package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.client.ClientChannel;
import com.agent.software.computers.Computer;
import com.agent.software.event.Event;
import com.agent.software.event.EventType;
import com.agent.software.event.Priority;
import com.agent.software.event.Task;
import com.agent.software.llm.LLM;
import com.agent.software.llm.OpenAICompatLLM;
import com.agent.software.llm.Response;
import com.agent.software.llm.context.Context;
import com.agent.software.llm.context.SemanticMemory;
import com.agent.software.store.NoteStore;
import com.agent.software.tools.Tool;
import com.agent.software.tools.ToolResult;
import com.agent.software.tools.Toolkit;
import com.agent.software.tools.Toolkits;
import com.agent.software.utils.Data;
import com.agent.software.utils.Json;
import com.agent.software.utils.UUIDObject;
import com.agent.software.web.ChatStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一个员工（大组成员）的运行时实例：配置 + 状态 + 事件队列 + 工具 + 会话 + 生命周期。
 *
 * <p>只持有"角色专属"的协作者（LLM / Context / Computer）；其它一律通过 {@link #getSystem()} 取。
 * 假死员工没有本类实例，只有 {@link Employee}。
 *
 * <p>线程契约：
 * <ul>
 *   <li>{@link #enqueue(Event)} 可被事件总线线程调用；{@link #pollEvent} 只由本角色 worker 调用。</li>
 *   <li>{@link #onShiftStart()} / {@link #onShiftEnd()} 由时间线程直调（worker 可能正阻塞在等待里）。</li>
 *   <li>未 {@link #setup()} 就访问 {@link #getComputer()} / {@link #getContext()} / {@link #getLlm()} 抛
 *       {@link IllegalStateException}（不懒创建）。</li>
 * </ul>
 */
public final class Role extends UUIDObject implements Data {

    private static final Logger logger = LoggerFactory.getLogger(Role.class);

    /** 一次任务内最多几轮工具调用。 */
    private static final int MAX_TOOL_ROUNDS = 20;
    /** 连续多少轮工具失败就放弃（错误已回喂，给模型几次自我纠正的机会，避免无限烧 token）。 */
    private static final int MAX_FAILING_ROUNDS = 3;
    /** 最近任务历史保留条数（供 my_tasks 查看）。 */
    private static final int TASK_HISTORY_LIMIT = 100;

    // ── 配置（从 Employee.template 构建；非 final，见报备项 B1）──
    public String roleId;
    public String name;
    public String username;
    public int uid;
    public String title;
    public String group;
    public String responsibilities;
    public String personality;
    public List<String> skills = new ArrayList<>();
    public String email;

    // ── 运行时 ──
    private volatile RoleState state = RoleState.IDLE;
    private AgentSystem system;
    private Employee employee;
    private Context context;
    private LLM llm;
    private Computer computer;
    private NoteStore noteStore;
    private boolean setupDone = false;
    private volatile boolean running = false;
    private Thread worker;

    private final List<Toolkit> toolkits = new CopyOnWriteArrayList<>();
    private final List<String> journal = new CopyOnWriteArrayList<>();
    /** 最近处理完的任务（成功/失败都留痕，供 my_tasks 查看 —— 任务静默失败过一次）。 */
    private final List<Task> taskHistory = new CopyOnWriteArrayList<>();

    // 优先级队列：同优先级按入队序号 FIFO
    private record Slot(long seq, Event event) {
    }

    private final ReentrantLock queueLock = new ReentrantLock();
    private final Condition queueSignal = queueLock.newCondition();
    private final PriorityQueue<Slot> queue = new PriorityQueue<>(
            Comparator.comparingInt((Slot s) -> -s.event().priority.value)
                    .thenComparingLong(Slot::seq));
    private long seqCounter = 0;

    // talkTo 等待交接
    private final Object waitLock = new Object();
    private volatile String waitingFor;
    private String pendingReply;
    private boolean waitAborted;
    private String abortMessage;

    public Role(Employee employee) {
        super();
        applyEmployee(employee);
    }

    public Role(String uuid, Employee employee) {
        super(uuid);
        applyEmployee(employee);
    }

    /** AgentSystem / RolePool / Staffing（同包）绑定所属系统。 */
    void bind(AgentSystem system) {
        this.system = system;
    }

    private void applyEmployee(Employee e) {
        this.employee = e;
        if (e == null) {
            this.roleId = "";
            this.name = "";
            this.group = "";
            return;
        }
        this.roleId = e.roleId;
        this.name = e.name;
        this.group = e.group;
        this.username = e.templateString("username", e.roleId);
        this.title = e.templateString("title", "");
        this.responsibilities = e.templateString("responsibilities", "");
        this.personality = e.templateString("personality", "");
        this.skills = new ArrayList<>(e.templateList("skills"));
        this.email = e.templateString("email", "");
        this.uid = Integer.parseInt(e.templateString("uid", "0"));
    }

    // ── 状态 ────────────────────────────────────────────────────

    public RoleState getState() {
        return state;
    }

    public void setState(RoleState s) {
        if (s == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        this.state = s;
    }

    public boolean isBusy() {
        return state == RoleState.BUSY;
    }

    public boolean isIdle() {
        return state == RoleState.IDLE;
    }

    public boolean isWaiting() {
        return state == RoleState.WAIT;
    }

    // ── 事件队列 ────────────────────────────────────────────────

    public void enqueue(Event e) {
        if (e == null) {
            return;
        }
        queueLock.lock();
        try {
            queue.add(new Slot(seqCounter++, e));
            queueSignal.signalAll();
        } finally {
            queueLock.unlock();
        }
    }

    /** 队列为空时最多等 timeoutMillis；返回 null 表示超时（用于让 worker 能响应 stop）。 */
    public Event pollEvent(long timeoutMillis) throws InterruptedException {
        queueLock.lock();
        try {
            if (queue.isEmpty() && timeoutMillis > 0) {
                queueSignal.await(timeoutMillis, TimeUnit.MILLISECONDS);
            }
            Slot slot = queue.poll();
            return slot == null ? null : slot.event();
        } finally {
            queueLock.unlock();
        }
    }

    public Event peekEvent() {
        queueLock.lock();
        try {
            Slot slot = queue.peek();
            return slot == null ? null : slot.event();
        } finally {
            queueLock.unlock();
        }
    }

    public int queueDepth() {
        queueLock.lock();
        try {
            return queue.size();
        } finally {
            queueLock.unlock();
        }
    }

    /** 队列快照，按 worker 的取用顺序（优先级高在前，同优先级 FIFO）。 */
    public List<Event> pendingEvents() {
        queueLock.lock();
        try {
            List<Slot> slots = new ArrayList<>(queue);
            slots.sort(Comparator.comparingInt((Slot s) -> -s.event().priority.value)
                    .thenComparingLong(Slot::seq));
            List<Event> out = new ArrayList<>(slots.size());
            for (Slot s : slots) {
                out.add(s.event());
            }
            return out;
        } finally {
            queueLock.unlock();
        }
    }

    /** 最近处理完的任务（新的在前，最多 limit 条）。 */
    public List<Task> taskHistory(int limit) {
        List<Task> all = List.copyOf(taskHistory);
        if (limit <= 0 || all.size() <= limit) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    private void rememberTask(Task t) {
        if (t == null || !t.isFinished()) {
            return;
        }
        taskHistory.add(t);
        while (taskHistory.size() > TASK_HISTORY_LIMIT) {
            taskHistory.remove(0);
        }
    }

    // ── 生命周期 ────────────────────────────────────────────────

    /** 自装配：LLM / Context / Computer / 默认工具。只允许调用一次。 */
    public void setup() {
        if (system == null) {
            throw new IllegalStateException("role not bound to a system: " + roleId);
        }
        if (setupDone) {
            throw new IllegalStateException("role already set up: " + roleId);
        }
        this.context = new Context();
        // 语义记忆：每条消息入上下文时算向量，条数超阈值就把离新消息最远的一条移出 prompt
        // （remember=false，但仍在内存里，search_memory 依然能检索到）。
        this.context.setMemory(SemanticMemory.fromConfig(system.getConfigStore()));
        if (this.context.memory().enabled()) {
            logger.info("Role[{}] semantic memory: model={}, threshold={}", roleId,
                    this.context.memory().embedding().getModel(), this.context.memory().threshold());
        } else {
            logger.info("Role[{}] semantic memory off (no embedding.model configured)", roleId);
        }
        if (this.llm == null) {
            // 传 null 让 OpenAICompatLLM 按 环境变量 > 配置文件 > 默认值 解析；
            // 传死默认值会把 config.json 里的 llm.model / llm.api_key 顶掉。
            this.llm = new OpenAICompatLLM(null, null, system.getConfigStore());
        }
        this.llm.setContext(this.context);
        this.llm.setSystemPrompt(buildSystemPrompt());
        this.computer = system.getComputerManager().create(computerKind(), this);
        try {
            this.computer.powerOn();   // 未指定电脑时默认 podman：创建即上电（容器随之创建/启动）
        } catch (Exception e) {
            logger.warn("Role[{}] failed to power on computer at setup", roleId, e);
        }
        this.toolkits.addAll(Toolkits.defaults(this, system.getToolkitConfig(),
                system.getMailService(), system.getMcpManager(), system.getSkillManager()));
        this.llm.setTools(getTools());   // 把工具声明交给 LLM，否则请求不带 tools 字段、模型无法调用
        this.setupDone = true;
        logger.info("Role[{}] setup: {} toolkit(s)", roleId, toolkits.size());
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        if (!setupDone) {
            setup();
        }
        running = true;
        worker = Thread.ofVirtual().name("role-" + roleId).start(this::work);
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        abortWait("[system] role stopped");
    }

    public boolean isRunning() {
        return running;
    }

    /** 上班：由时间线程直调。 */
    public void onShiftStart() {
        // 没有 OFF_DUTY/PAUSED，上班不需要改变状态；清掉上一班次留下的中断标记即可
        synchronized (waitLock) {
            waitAborted = false;
            abortMessage = null;
        }
        if (computer != null && !computer.isOn()) {
            try {
                computer.powerOn();
            } catch (Exception e) {
                logger.warn("Role[{}] failed to power on computer at shift start", roleId, e);
            }
        }
        journal("Shift start");
    }

    /** 下班：由时间线程直调；终止正在等待的 talkTo / 客户对话，并把上下文旧消息移出 prompt。 */
    public void onShiftEnd() {
        if (isWaiting()) {
            abortWait("[shift end] the colleague you were waiting for is off duty; treat this as their reply.");
        }
        if (system != null && system.getClientChannel() != null) {
            // talk_to_client 的等待在客户端通道里，不在 waitForReply 上，必须单独打断
            system.getClientChannel().cancelWait(
                    "[shift end] the client conversation is closed for today; continue tomorrow.");
        }
        if (context != null) {
            context.forgetAll();
            context.endDay(context.getDay());
        }
        journal("Shift end");
    }

    // ── 协作者 ──────────────────────────────────────────────────

    public AgentSystem getSystem() {
        return system;
    }

    public Computer getComputer() {
        if (computer == null) {
            throw new IllegalStateException("computer not created; call setup() first: " + roleId);
        }
        return computer;
    }

    public boolean hasComputer() {
        return computer != null;
    }

    public Context getContext() {
        if (context == null) {
            throw new IllegalStateException("context not created; call setup() first: " + roleId);
        }
        return context;
    }

    public LLM getLlm() {
        if (llm == null) {
            throw new IllegalStateException("llm not created; call setup() first: " + roleId);
        }
        return llm;
    }

    public void setLlm(LLM llm) {
        this.llm = llm;
        if (llm != null && context != null) {
            llm.setContext(context);
        }
    }

    /**
     * 本角色的笔记库（{@code <dataDir>/notes/<roleId>}，懒创建）。
     *
     * <p>上下文每天下班会被清出 prompt，所以"跨天要记住的事"必须落到笔记里。
     */
    public synchronized NoteStore noteStore() {
        if (noteStore == null) {
            Path base = system == null ? null : system.getDataDir();
            noteStore = new NoteStore(base == null ? null : base.resolve("notes"), roleId);
        }
        return noteStore;
    }

    // ── 工具 ────────────────────────────────────────────────────

    public void addToolkit(Toolkit t) {
        if (t != null) {
            toolkits.add(t);
            refreshLlmTools();
        }
    }

    public void addTool(Tool t) {
        if (t == null) {
            return;
        }
        // Toolkit.addTool 是 protected（跨包不能直接调），放进匿名子类的初始化块里调用
        Toolkit single = new Toolkit() {
            {
                addTool(t);
            }
        };
        toolkits.add(single);
        refreshLlmTools();
    }

    private void refreshLlmTools() {
        if (llm != null) {
            llm.setTools(getTools());
        }
    }

    public List<Tool> getTools() {
        List<Tool> out = new ArrayList<>();
        for (Toolkit t : toolkits) {
            out.addAll(t.getTools());
        }
        return out;
    }

    /** 执行一个工具；失败/异常都包成 {@link ToolResult}，错误文本要回喂模型。 */
    public ToolResult invokeTool(String name, Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        for (Toolkit t : toolkits) {
            for (Tool tool : t.getTools()) {
                if (tool.getToolName().equals(name)) {
                    try {
                        String out = tool.handler(safeArgs);
                        String text = out == null ? "" : out;
                        return new ToolResult(!isToolFailure(text), text);
                    } catch (Exception e) {
                        logger.warn("Role[{}] tool {} failed", roleId, name, e);
                        return new ToolResult(false, "tool error: " + e.getMessage());
                    }
                }
            }
        }
        return new ToolResult(false, "unknown tool: " + name);
    }

    /** 工具用文本报错（不抛异常），这里统一判定成败。 */
    private static boolean isToolFailure(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String s = text.stripLeading();
        if (com.agent.software.core.Types.isFailureText(s)) {
            return true;
        }
        String lower = s.toLowerCase();
        return lower.startsWith("[error") || lower.startsWith("error")
                || lower.contains(" error:") || lower.contains("failed:");
    }

    // ── 对话 ────────────────────────────────────────────────────

    /** talk 规则：目标必须在大组；同部门可聊，管理组豁免部门限制。 */
    public boolean canTalkTo(Role target) {
        if (target == null || target == this) {
            return false;
        }
        RolePool pool = system == null ? null : system.getRolePool();
        if (pool == null || pool.find(target.roleId) == null) {
            return false;
        }
        if (pool.isManagement(this)) {
            return true;
        }
        return group != null && group.equals(target.group);
    }

    /**
     * 发送一条 talk。目标正在等我回复时直接交接，否则投 TALK 事件。
     *
     * @return 面向调用方的结果文本（失败也是文本，不抛异常）
     */
    public String talkTo(String targetRoleId, String message, Priority urgency) {
        if (system == null) {
            return "talk failed: role not bound to a system";
        }
        Role target = system.getRolePool().find(targetRoleId);
        if (target == null) {
            return "talk failed: no such active role '" + targetRoleId + "'";
        }
        if (!canTalkTo(target)) {
            return "talk failed: " + roleId + " (" + group + ") cannot talk to " + target.roleId
                    + " (" + target.group + "); cross-department talk is limited to the management group";
        }
        Priority p = urgency == null ? Priority.NORMAL : urgency;
        // 对方正在等我 → 直接交接，不走事件队列
        if (target.isWaiting() && roleId.equals(target.waitingFor)) {
            target.deliverReply(message);
            journal("Replied to waiting " + target.roleId);
            return "talk: replied to " + target.roleId + " who was waiting";
        }
        Event e = Event.builder()
                .from(roleId)
                .to(target.roleId)
                .type(EventType.TALK)
                .priority(p)
                .content(message)
                .source("talk")
                .build();
        system.getEventBus().post(e);
        chat("talk", message, p.name(), Map.of("target", target.roleId));
        journal("Sent talk to " + target.roleId + " (" + p + ")");
        return "talk: message sent to " + target.roleId + ", urgency=" + p
                + ", queue depth=" + target.queueDepth();
    }

    /** 与甲方口头沟通；由 ClientChannel 保证同一时间只有一个角色。 */
    public String talkToClient(String message, boolean wait) {
        if (system == null) {
            return "talk_to_client failed: role not bound to a system";
        }
        ClientChannel channel = system.getClientChannel();
        if (channel == null) {
            return "talk_to_client failed: no client channel";
        }
        chat("client", message, "", Map.of("target", "CLIENT"));
        return channel.receiveFrom(roleId, message);
    }

    /** 阻塞等待某人的回复；超过 timeoutMillis 或被中断/下班中止时返回提示文本。 */
    public String waitForReply(String targetRoleId, long timeoutMillis) {
        beginWait(targetRoleId);
        try {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            synchronized (waitLock) {
                while (pendingReply == null && !waitAborted) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) {
                        return "[talk] wait timed out after " + timeoutMillis + "ms";
                    }
                    try {
                        waitLock.wait(remain);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return "[talk] wait interrupted";
                    }
                }
                if (waitAborted) {
                    return abortMessage == null ? "[talk] wait aborted" : abortMessage;
                }
                String reply = pendingReply;
                pendingReply = null;
                return reply;
            }
        } finally {
            endWait();
        }
    }

    void beginWait(String targetRoleId) {
        synchronized (waitLock) {
            this.waitingFor = targetRoleId;
            this.pendingReply = null;
            this.waitAborted = false;
            this.abortMessage = null;
        }
        setState(RoleState.WAIT);
    }

    void endWait() {
        synchronized (waitLock) {
            this.waitingFor = null;
            this.pendingReply = null;
        }
        if (state == RoleState.WAIT) {
            setState(RoleState.IDLE);
        }
    }

    /** 对方回复时直接交接（同包调用，不对外）。 */
    void deliverReply(String content) {
        synchronized (waitLock) {
            this.pendingReply = content == null ? "" : content;
            waitLock.notifyAll();
        }
    }

    /** 中止等待：下班、停机、系统强制收工时由时间线程调用。 */
    void abortWait(String message) {
        synchronized (waitLock) {
            if (waitingFor == null) {
                return;
            }
            this.waitAborted = true;
            this.abortMessage = message;
            waitLock.notifyAll();
        }
    }

    // ── 日志 / trace ────────────────────────────────────────────

    public void journal(String entry) {
        String line = "[" + java.time.LocalDateTime.now().withNano(0) + "] " + entry;
        journal.add(line);
        while (journal.size() > 2000) {
            journal.remove(0);
        }
        logger.debug("Role[{}] {}", roleId, entry);
    }

    public List<String> readJournal() {
        return List.copyOf(journal);
    }

    public void recordReasoning(String text, String taskId, Integer round) {
        // 角色输出全部进日志（INFO，不截断）——journal 走的是 DEBUG，默认看不到
        logger.info("Role[{}] reasoning (task={} round={}):\n{}", roleId, taskId, round, text);
        journal("reasoning(task=" + taskId + ", round=" + round + "): " + text);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("task", safe(taskId));
        extra.put("round", String.valueOf(round));
        chat("reason", text, "", extra);
    }

    public void recordNote(String content, String taskId, Integer round) {
        logger.info("Role[{}] note (task={} round={}):\n{}", roleId, taskId, round, content);
        journal("note(task=" + taskId + ", round=" + round + "): " + content);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("task", safe(taskId));
        extra.put("round", String.valueOf(round));
        chat("note", content, "", extra);
    }

    public void recordToolCall(String tool, String args, String result, String taskId, Integer round) {
        logger.info("Role[{}] tool {} (task={} round={}):\n  args={}\n  result={}",
                roleId, tool, taskId, round, args, result);
        journal("tool(" + tool + ", task=" + taskId + ", round=" + round + "): args=" + args
                + " result=" + result);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("tool", tool);
        extra.put("args", args);
        extra.put("result", result);
        extra.put("task", safe(taskId));
        extra.put("round", String.valueOf(round));
        chat("tool", tool, "", extra);
    }

    public void recordAnswer(String text, String taskId, String status, Integer tokens) {
        logger.info("Role[{}] answer (task={} status={} tokens={}):\n{}",
                roleId, taskId, status, tokens, text);
        journal("answer(task=" + taskId + ", status=" + status + ", tokens=" + tokens + "): " + text);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("status", status == null ? "" : status);
        extra.put("tokens", tokens == null ? 0 : tokens);
        extra.put("task", safe(taskId));
        chat("answer", text, "", extra);
    }

    /** 向 Web UI 的活动流推一条消息（reason/note/tool/answer/talk/client）。 */
    private void chat(String kind, String text, String urgency, Map<String, Object> extra) {
        if (system == null || system.getChatStore() == null) {
            return;
        }
        try {
            ChatStore.ChatMessage m = system.getChatStore().record(
                    kind, group == null ? "" : group, roleId,
                    name == null ? roleId : name, "", "", text, urgency == null ? "" : urgency);
            if (extra != null) {
                m.extra.putAll(extra);
            }
        } catch (Exception e) {
            logger.warn("Role[{}] failed to record chat message", roleId, e);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ── 持久化 ──────────────────────────────────────────────────

    @Override
    public Map<String, String> getData() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("uuid", uuid);
        d.put("role_id", roleId == null ? "" : roleId);
        d.put("name", name == null ? "" : name);
        d.put("group", group == null ? "" : group);
        d.put("email", email == null ? "" : email);
        d.put("state", state.name());
        return d;
    }

    @Override
    public void loadData(Map<String, String> data) {
        if (data == null) {
            return;
        }
        if (data.containsKey("uuid")) {
            this.uuid = data.get("uuid");
        }
        this.roleId = data.getOrDefault("role_id", this.roleId);
        this.name = data.getOrDefault("name", this.name);
        this.group = data.getOrDefault("group", this.group);
        this.email = data.getOrDefault("email", this.email);
        this.state = RoleState.valueOf(data.getOrDefault("state", RoleState.IDLE.name()));
    }

    // ── worker ──────────────────────────────────────────────────

    private void work() {
        logger.info("Role[{}] worker started ({})", roleId, group);
        while (running) {
            Event e;
            try {
                // 等回复期间（WAIT）不取事件：队列非空时 pollEvent 会立即返回，
                // 任务被 runTask"取出→塞回"来回折腾，形成 100% CPU 的忙等空转。
                // 等 endWait() 把状态置回 IDLE 再继续（阻塞在 waitForReply 里的就是本线程，
                // 所以正常路径下这里不会命中，是给外部 setState(WAIT) 兜底的）。
                if (state == RoleState.WAIT) {
                    Thread.sleep(200);
                    continue;
                }
                e = pollEvent(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (e == null) {
                continue;
            }
            try {
                dispatch(e);
            } catch (Exception ex) {
                logger.error("Role[{}] failed handling {}", roleId, e, ex);
                if (state == RoleState.BUSY) {
                    setState(RoleState.IDLE);
                }
            }
        }
        logger.info("Role[{}] worker stopped", roleId);
    }

    private void dispatch(Event e) {
        if (e.type == EventType.SHIFT_START) {
            onShiftStart();
            // 唤醒：原实现在上班时把这条广播事件也变成一条任务，每个大组成员因此真的跑一轮
            // （查收件箱、接着昨天没干完的活、回报）。refactor3 首版只改状态就 return，
            // 于是每天 08:00 没有任何角色被叫醒 —— 这是"没人干活"的一半原因。
            String wake = e.content == null || e.content.isBlank() ? "Shift start" : e.content;
            runTask(new Task(e.fromRoleId, roleId, System.currentTimeMillis(),
                    "[time] " + wake, e.priority));
            return;
        }
        if (e.type == EventType.SHIFT_END) {
            onShiftEnd();
            return;
        }
        if (e instanceof Task task) {
            runTask(task);
            return;
        }
        if (e.type == EventType.TALK) {
            runTask(new Task(e.fromRoleId, roleId, System.currentTimeMillis(),
                    "[talk] " + e.content, e.priority));
            return;
        }
        if (e.type == EventType.NEW_MAIL) {
            String mailId = e.payload == null ? null : safe(String.valueOf(e.payload.get("message_id")));
            if (!mailId.isBlank() && mailAlreadyRead(mailId)) {
                // 通知积压：前面的任务可能已经把收件箱读完了，这条通知已无意义。
                // 直接跳过，不再白烧一轮 LLM（这是"重复邮件通知"投诉的根源）。
                journal("Skip duplicate NEW_MAIL notification (already read): " + mailId);
                return;
            }
            runTask(new Task(e.fromRoleId, roleId, System.currentTimeMillis(),
                    "[mail] " + e.content, e.priority));
        }
    }

    /** 这封邮件是否已读（查不到按未读处理，保证正常流程不受影响）。 */
    private boolean mailAlreadyRead(String messageId) {
        if (system == null || system.getMailService() == null) {
            return false;
        }
        String address = system.getMailService().getAddress(roleId);
        for (com.agent.software.services.MailMessage m : system.getMailService().inbox(address, null)) {
            if (messageId.equals(m.messageId)) {
                return m.read;
            }
        }
        return false;
    }

    private void runTask(Task task) {
        if (state == RoleState.WAIT) {
            // 正在等回复，不接新任务；退回队列下轮再处理
            enqueue(task);
            return;
        }
        setState(RoleState.BUSY);
        task.markRunning();
        int tokens = 0;
        String answer = "";
        int failingRounds = 0;
        try {
            logger.info("Role[{}] task input ({}):\n{}", roleId, task.uuid, task.content);
            getLlm().appendUserMessage(task.content);
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                Response r = getLlm().request();
                tokens += r.getTotalTokens();
                if (r.reasoning != null && !r.reasoning.isBlank()) {
                    recordReasoning(r.reasoning, task.uuid, round);
                }
                if (!r.hasToolCalls()) {
                    answer = r.text == null ? "" : r.text;
                    getLlm().appendAssistantMessage(answer);
                    break;
                }
                if (r.text != null && !r.text.isBlank()) {
                    logger.info("Role[{}] assistant text (task={} round={}):\n{}",
                            roleId, task.uuid, round, r.text);
                }
                // 必须先把带 tool_calls 的 assistant 消息写回上下文：
                // 否则下一轮只有 tool 结果、没有对应的 function_call，部分网关（如 Hanseq）
                // 会因为 "function_call_output requires item_reference ids matching each call_id" 直接 400。
                getLlm().appendAssistantMessage(r.text, r.toolCalls);
                boolean anyFailed = false;
                boolean restRequested = false;
                for (Map<String, Object> call : r.toolCalls) {
                    String callId = toolCallId(call);
                    String toolName = toolName(call);
                    logger.info("Role[{}] tool call: id={} name={}", roleId, callId, toolName);
                    Map<String, Object> args = toolArgs(call);
                    ToolResult res = invokeTool(toolName, args);
                    getLlm().appendToolResult(callId, toolName, res.text);
                    recordToolCall(toolName, Json.stringify(args), res.text, task.uuid, round);
                    if (!res.ok) {
                        // 关键：所有 tool_call 都必须回喂结果，否则下一轮 call_id 对不上；
                        // 失败也不立刻结束任务，把错误文本交给模型让它自己纠正。
                        anyFailed = true;
                        answer = "tool failed: " + res.text;
                    }
                    if ("take_rest".equals(toolName)) {
                        // take_rest 的语义是"这件事到此为止，我去休息"。必须结束当前任务：
                        // 否则模型会被反复追问同一件事，继续 take_rest / read_mail 空转
                        // 到 MAX_TOOL_ROUNDS（实测 20 轮、18 万 token 仍无产出）。
                        answer = res.text;
                        restRequested = true;
                    }
                }
                if (restRequested) {
                    break;
                }
                if (anyFailed) {
                    failingRounds++;
                    if (failingRounds >= MAX_FAILING_ROUNDS) {
                        break;
                    }
                } else {
                    failingRounds = 0;
                }
            }
            if (answer.isEmpty()) {
                answer = "(no answer)";
            }
            task.markDone(answer, tokens);
            recordAnswer(answer, task.uuid, task.status, tokens);
        } catch (Exception ex) {
            logger.error("Role[{}] task {} failed", roleId, task.uuid, ex);
            task.markFailed("task error: " + ex.getMessage());
            recordAnswer(task.result, task.uuid, task.status, tokens);
        } finally {
            rememberTask(task);
            setState(RoleState.IDLE);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 工具调用 id：不同网关有的给 id、有的给 call_id，取到哪个用哪个。 */
    private static String toolCallId(Map<String, Object> call) {
        String id = str(call.get("id"));
        if (id.isBlank()) {
            id = str(call.get("call_id"));
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    private static String toolName(Map<String, Object> call) {
        Object fn = call.get("function");
        if (fn instanceof Map<?, ?> m) {
            return str(m.get("name"));
        }
        return str(call.get("name"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolArgs(Map<String, Object> call) {
        Object fn = call.get("function");
        Object raw = fn instanceof Map<?, ?> m ? m.get("arguments") : call.get("arguments");
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Json.parseObject(s);
            } catch (Exception ex) {
                // 模型的 arguments 偶尔不是合法 JSON：不能让整个任务崩掉（日志里出现过 UncheckedIOException）
                logger.warn("Role[{}] tool arguments are not valid JSON, using empty args: {}",
                        roleId, s);
                return Map.of();
            }
        }
        return Map.of();
    }

    private String computerKind() {
        String kind = employee == null ? null : employee.templateString("computer_kind", null);
        if (kind == null || kind.isBlank()) {
            // 未指定电脑时默认 podman；无 podman 环境可用 AGENTSOFTWARE_COMPUTER_KIND=local 覆盖
            kind = System.getenv().getOrDefault("AGENTSOFTWARE_COMPUTER_KIND", "podman");
        }
        return kind;
    }

    /**
     * 组装角色的 System Prompt。
     *
     * <p>对齐 master {@code AgentRole.buildSystemPrompt()} / refactor2 {@code SystemPrompt.build()}：
     * 人设 + 当前时间 + 云盘/Git/邮件规则 + talk 范围 + 模板里的 {@code system_prompt_extra}。
     *
     * <p>注意：refactor3 首版这里只剩"人设三行"，把上面这些全丢了。最要命的是
     * {@code system_prompt_extra} 从未注入 —— 54 个模板里有 52 个带这个字段，CEO/COO/HR/CTO/
     * business_analyst 的**完整工作流定义**（谁向谁要什么、走什么流程）都在里面，
     * 不进提示词角色自然不知道自己该干活、该找谁。
     */
    private String buildSystemPrompt() {
        List<String> parts = new ArrayList<>();
        String shownTitle = title == null || title.isBlank() ? roleId : title;
        parts.add("You are " + name + ", your title is " + shownTitle
                + ", working as the " + roleId + " role.");
        if (responsibilities != null && !responsibilities.isBlank()) {
            parts.add("Responsibilities: " + sentence(responsibilities));
        }
        if (personality != null && !personality.isBlank()) {
            parts.add("Personality: " + sentence(personality));
        }
        if (!skills.isEmpty()) {
            parts.add("Skills: " + String.join(", ", skills) + ".");
        }
        parts.add(clockLine());
        parts.add("If you currently have no task, you may directly rest. "
                + "Also note: do not send messages to others when you should not be disturbing them; "
                + "only send when necessary. So when you have no task, do not ask others anything, "
                + "just rest. You will be notified automatically when something comes up. "
                + "After you finish a task, report the completion to the colleague who assigned it, "
                + "then rest.");
        parts.add("If you have a task that involves communicating with someone, make sure to do it "
                + "at the scheduled time — not early, not late — because the other party expects you "
                + "to contact them at that time.");
        parts.add("The company cloud drive is at /mnt/drive (every computer mounts the same shared folder):\n"
                + "  - /mnt/drive/Public — public shared directory, readable and writable by all employees "
                + "(put shared resources, announcements, and collaboration files here)\n"
                + "  - /mnt/drive/" + username + " — your personal directory; only you can write to it; "
                + "other employees have read-only access\n"
                + "  - Other employees' personal directories are read-only for you as well\n"
                + "Use the computer's file commands directly for file operations (ls / cat / cp / mv / rm, etc.); "
                + "to share a file with a colleague: write it to Public, or send the cloud drive file path "
                + "via the talk attachment parameter.");
        parts.add("The company uses Git to manage project code (multi-person collaboration, multiple projects):\n"
                + "  - Each project is one repository; code is kept in its own repository per project\n"
                + "  - Run git commands on your personal computer (git clone / branch / add / commit / push / merge, etc.)\n"
                + "  - After completing a feature: first git pull to get the latest code, commit "
                + "(with a clear description of what and why), then push to merge into the main branch "
                + "or open a merge request\n"
                + "  - When collaborating with others on the same project, sync the latest code first (git pull) "
                + "to avoid conflicts; when a conflict occurs, communicate with the relevant colleagues "
                + "before merging\n"
                + "  - The main branch must always remain usable; do not force-overwrite others' code "
                + "without permission\n"
                + "For changes that need collaboration with colleagues, discuss the division of work first, "
                + "then commit and merge.");
        parts.add("Company email: every employee has a company mailbox, and employees communicate via email "
                + "(send_email to send / read_mail to receive).");
        parts.add("Personal notes and scheduled tasks:\n"
                + "  - Notes (write_note / read_note / edit_note / delete_note / list_notes) are your own "
                + "long-term memory, stored as files that survive across days. Your conversation context "
                + "is cleared every night, so anything you must still remember tomorrow (decisions, file "
                + "paths, who owes what, progress made) has to be written into a note.\n"
                + "  - Tasks (create_task / list_tasks / update_task / delete_task / my_tasks) let you "
                + "schedule work for a later simulated time (in_minutes, or day + tick where tick 0 = "
                + "08:00 and 36000 = 18:00). When something must happen later — a follow-up, a deadline "
                + "reminder, handing work to the next shift — schedule a task instead of resting and "
                + "waiting in a loop: the task wakes the assignee when it is due. list_tasks shows what "
                + "is scheduled but not due yet; my_tasks shows what is already in your queue plus how "
                + "your recent tasks ended.");
        if (context != null && context.memory() != null && context.memory().enabled()) {
            parts.add("Memory search: search_memory(query, limit?) finds the most semantically similar "
                    + "messages from everything you have read, said or done before — including older "
                    + "messages that have been dropped from the recent prompt to save context. Use it when "
                    + "you half-remember an earlier decision, file path, instruction or agreement, instead "
                    + "of guessing.");
        }
        if (group != null && !group.isBlank()) {
            parts.add("You belong to the " + group + ", and your company email is " + mailAddress() + ". "
                    + "Colleague communication rules: the talk tool can only message members of your own group "
                    + "(quick in-group communication); communication with colleagues in other groups "
                    + "(other teams, release management, leadership, etc.) must use email "
                    + "(send_email to send, read_mail to check the inbox).");
        }
        String extra = employee == null ? "" : employee.templateString("system_prompt_extra", "");
        if (extra != null && !extra.isBlank()) {
            parts.add(extra);
        }
        if ("COO".equals(roleId)) {
            parts.add(cooStaffingRules());
        }
        parts.add("Work only through the provided tools and report results concisely.");
        return String.join("\n", parts);
    }

    /**
     * COO 专属：把"公司名单 ≠ 当前大组"这条系统事实说清。
     *
     * <p>工作流（怎么拆、怎么派）写在模板的 {@code system_prompt_extra} 里；这里只补系统事实，
     * 因为它是运行时的、按角色生效的，模板不该承担。refactor3 只准入管理组，其余人全是
     * {@code OUT_OF_GROUP} 的"假死"状态，而**只有 COO 有 draft_in**（D15）——实测不写清楚，
     * COO 会把派工推给 CTO（没有该工具），于是全公司永远没人被拉进组、没人干活。
     */
    private static String cooStaffingRules() {
        return "[Staffing] Only you can change the cohort: roster entries are OUT_OF_GROUP "
                + "(list_employees marks them) and dormant — no Role, no computer, no tools, and they "
                + "receive no mail and no talk — until you call draft_in for them. Handing staffing to "
                + "the CTO or HR leaves the work unassigned: no other role has draft_in.";
    }

    /** 当前日期 / 班次 / 时钟语义：让角色知道今天是第几天、几点、工期多长。 */
    private String clockLine() {
        if (system == null || system.getTimeBus() == null) {
            return "The simulated clock is not available yet.";
        }
        var tb = system.getTimeBus();
        // SHIFT_START_SECONDS 在 TimeBus 里是 private 常量（08:00），这里按同一基准换算
        long shiftStartSeconds = 8L * 3600L;
        long shiftEndSeconds = shiftStartSeconds + tb.getShiftEndTick();
        return "Today is " + tb.currentDate() + " (day " + tb.getDay() + "), company shift "
                + hhmm(shiftStartSeconds) + "–" + hhmm(shiftEndSeconds)
                + " (1 tick = 1 simulated second, tick 0 of the shift = " + hhmm(shiftStartSeconds) + "). "
                + "Current simulated time: " + tb.currentDateTime() + ".";
    }

    private static String hhmm(long seconds) {
        return java.time.LocalTime.MIDNIGHT.plusSeconds(Math.floorMod(seconds, 86_400L)).toString();
    }

    /** 模板里的句子多数自带句号，拼接时避免出现 ".."。 */
    private static String sentence(String s) {
        String t = s.strip();
        if (t.isEmpty()) {
            return t;
        }
        char last = t.charAt(t.length() - 1);
        return switch (last) {
            case '.', '!', '?', '。', '！', '？' -> t;
            default -> t + ".";
        };
    }

    /** 提示词里展示的公司邮箱；查不到时退化成 username@company.local。 */
    private String mailAddress() {
        if (system != null && system.getMailService() != null && roleId != null && !roleId.isBlank()) {
            try {
                String address = system.getMailService().getAddress(roleId);
                if (address != null && !address.isBlank()) {
                    return address;
                }
            } catch (Exception e) {
                logger.debug("Role[{}] mail address lookup failed", roleId, e);
            }
        }
        return (username == null || username.isBlank() ? roleId : username) + "@company.local";
    }

    @Override
    public String toString() {
        return "Role(" + roleId + ", " + name + ", " + state + ")";
    }
}
