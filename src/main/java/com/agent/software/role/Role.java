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
import com.agent.software.tools.Tool;
import com.agent.software.tools.ToolResult;
import com.agent.software.tools.Toolkit;
import com.agent.software.tools.Toolkits;
import com.agent.software.utils.Data;
import com.agent.software.utils.Json;
import com.agent.software.utils.UUIDObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private boolean setupDone = false;
    private volatile boolean running = false;
    private Thread worker;

    private final List<Toolkit> toolkits = new CopyOnWriteArrayList<>();
    private final List<String> journal = new CopyOnWriteArrayList<>();

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
        if (this.llm == null) {
            this.llm = new OpenAICompatLLM(apiKey(), model(), system.getConfigStore());
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
                        return new ToolResult(true, out == null ? "" : out);
                    } catch (Exception e) {
                        logger.warn("Role[{}] tool {} failed", roleId, name, e);
                        return new ToolResult(false, "tool error: " + e.getMessage());
                    }
                }
            }
        }
        return new ToolResult(false, "unknown tool: " + name);
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

    public String getWaitingFor() {
        return waitingFor;
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
        journal("reasoning(task=" + taskId + ", round=" + round + "): " + text);
    }

    public void recordNote(String content, String taskId, Integer round) {
        journal("note(task=" + taskId + ", round=" + round + "): " + content);
    }

    public void recordToolCall(String tool, String args, String result, String taskId, Integer round) {
        journal("tool(" + tool + ", task=" + taskId + ", round=" + round + "): args=" + args
                + " result=" + result);
    }

    public void recordAnswer(String text, String taskId, String status, Integer tokens) {
        journal("answer(task=" + taskId + ", status=" + status + ", tokens=" + tokens + "): " + text);
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
            runTask(new Task(e.fromRoleId, roleId, System.currentTimeMillis(),
                    "[mail] " + e.content, e.priority));
        }
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
        try {
            getLlm().appendUserMessage(task.content);
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                Response r = getLlm().request();
                tokens += r.getTotalTokens();
                if (r.reasoning != null && !r.reasoning.isBlank()) {
                    recordReasoning(r.reasoning, task.uuid, round);
                }
                if (!r.hasToolCalls()) {
                    answer = r.text == null ? "" : r.text;
                    break;
                }
                boolean failed = false;
                for (Map<String, Object> call : r.toolCalls) {
                    String callId = str(call.get("id"));
                    String toolName = toolName(call);
                    Map<String, Object> args = toolArgs(call);
                    ToolResult res = invokeTool(toolName, args);
                    getLlm().appendToolResult(callId, toolName, res.text);
                    recordToolCall(toolName, Json.stringify(args), res.text, task.uuid, round);
                    if (!res.ok) {
                        failed = true;
                        answer = "tool failed: " + res.text;
                        break;
                    }
                }
                if (failed) {
                    break;
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
            setState(RoleState.IDLE);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
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
    private static Map<String, Object> toolArgs(Map<String, Object> call) {
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
            return Json.parseObject(s);
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

    private String apiKey() {
        return System.getenv().getOrDefault("OPENAI_API_KEY", "");
    }

    private String model() {
        return System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are ").append(name).append(" (").append(roleId).append(")");
        if (title != null && !title.isBlank()) {
            sb.append(", ").append(title);
        }
        sb.append(" in group \"").append(group).append("\".\n");
        if (responsibilities != null && !responsibilities.isBlank()) {
            sb.append("Responsibilities: ").append(responsibilities).append('\n');
        }
        if (personality != null && !personality.isBlank()) {
            sb.append("Personality: ").append(personality).append('\n');
        }
        if (!skills.isEmpty()) {
            sb.append("Skills: ").append(String.join(", ", skills)).append('\n');
        }
        sb.append("Work only through the provided tools and report results concisely.");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Role(" + roleId + ", " + name + ", " + state + ")";
    }
}
