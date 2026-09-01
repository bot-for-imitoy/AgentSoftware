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
 * 角色池 (RolePool) — Python 版 roles.py 的 RolePool.
 *
 * 管理所有角色并发运行: 每个角色一个常驻 daemon worker 线程, 循环:
 * 1. 弹出最高优先级任务 → 2. 用 OpenAI 兼容 LLM 执行 → 3. 触发回调 → 4. 重复.
 */
public class RolePool {

    private static final Logger logger = LoggerFactory.getLogger(RolePool.class);

    private final Map<String, AgentRole> roles = new LinkedHashMap<>();
    private int uidCounter = 0;                              // 容器内 uid 分配计数器 (1100 + 注册序号)
    private final ExecutorService executor;
    private final Map<String, Future<?>> futures = new LinkedHashMap<>();
    private final AtomicBoolean shutdownFlag = new AtomicBoolean(false);

    private String llmApiKey;
    private String llmModel;
    private TimeEventBus timeManager;                        // 共享时间源 (AgentSystem 注入)
    private boolean autoToolkits = true;                     // 默认工具装配开关
    private final AgentSystem owner;                         // 所属 AgentSystem (可为 null = 独立角色池)

    public RolePool(String llmApiKey, String llmModel,
                    TimeEventBus timeManager, boolean autoToolkits) {
        this(llmApiKey, llmModel, timeManager, autoToolkits, null);
    }

    /**
     * 携带所属 AgentSystem 构造: 角色装配/离职/LLM 配置均走该系统的独立协作对象
     * (电脑注册表/邮箱/MCP/技能/对话锁/数据目录), 使多个 AgentSystem 可在同一进程内
     * 安全共存. owner 为 null 时回退进程级全局默认 (独立角色池, 旧行为).
     */
    public RolePool(String llmApiKey, String llmModel,
                    TimeEventBus timeManager, boolean autoToolkits,
                    AgentSystem owner) {
        this.llmApiKey = llmApiKey;
        this.llmModel = llmModel;
        this.timeManager = timeManager;
        this.autoToolkits = autoToolkits;
        this.owner = owner;
        // 每角色一个常驻 worker: 用 Java 21+ 虚拟线程 — 不再受固定线程池
        // max_workers 上限约束 (46 角色完整团队也无需担心平台线程数),
        // 角色 worker 大量阻塞在 LLM HTTP 等待/队列轮询上, 虚拟线程开销极小.
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("role-", 0).factory());
    }

    public RolePool() {
        this(null, null, null, true);
    }

    // ── Role management ────────────────────────────────────

    /** 注册角色. 必须在 start() 前调用. */
    public void addRole(AgentRole role) {
        if (roles.containsKey(role.roleId)) {
            throw new IllegalArgumentException("Role '" + role.roleId + "' already exists");
        }
        roles.put(role.roleId, role);
        // 容器内 uid 分配: 1100 + 注册序号 (注册顺序稳定 → uid 跨重启稳定)
        if (role.uid <= 1100) {
            uidCounter++;
            role.uid = 1100 + uidCounter;
        }
        // 每个注册进池的角色都立即拥有专属活动日志
        role.journal("角色就位: " + role.name + " — " + (role.title.isEmpty() ? role.roleId : role.title));
    }

    /**
     * 角色装配 (唯一入口): 绑定共享时钟与所属系统 → 默认工具 → 默认 MCP 组. 幂等.
     */
    public void setupRole(AgentRole role) {
        // 绑定共享时间源: 必须早于 addToolkit(time)
        if (timeManager != null) {
            role.bindTimeManager(timeManager);
        }
        // 绑定所属系统: 电脑/邮箱/笔记/待办/日志等依赖全部走本系统实例
        if (owner != null) {
            role.bindSystem(owner);
        }
        // 默认工具 (memory/note/time/todo/task_view/pc/mcp_manager/skill_manager/email)
        for (Toolkit toolkit : Toolkits.defaultToolkits(role)) {
            role.addToolkit(toolkit);
        }
        // 默认 MCP 工具组 (如 file_ops 文件操作, 装到角色个人电脑)
        for (String group : Toolkits.DEFAULT_MCP_GROUPS) {
            role.mcpManager().installGroupDefaults(role, group);
        }
    }

    /** 按角色创建 LLM 客户端 (带角色日志前缀); 配置走 OpenAI 统一分层解析. */
    public LLM newLlm(String roleId) {
        return new OpenAICompatLLM(llmApiKey, null, llmModel, roleId,
                owner != null ? owner.configStore : null);
    }

    /** 动态入职: 注册新角色并立即启动其 worker 线程 (招聘流程用). */
    public AgentRole addRoleAndStart(AgentRole role) {
        if (roles.containsKey(role.roleId)) {
            throw new IllegalArgumentException("Role '" + role.roleId + "' already exists");
        }
        roles.put(role.roleId, role);
        if (autoToolkits) {
            setupRole(role);
        }
        startOneRole(role);
        role.journal("角色就位: " + role.name + " — " + (role.title.isEmpty() ? role.roleId : role.title));
        return role;
    }

    /** 启动单个角色 worker (start / addRoleAndStart 共用). */
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

    /** 按名取角色 (不存在返回 null). */
    public AgentRole getRoleOrNull(String name) {
        return roles.get(name);
    }

    /** 按人名查找角色 (talk 工具用); 兼容按 role_id 回退. */
    public AgentRole getRoleByName(String name) {
        for (AgentRole r : roles.values()) {
            if (r.name.equals(name)) {
                return r;
            }
        }
        return roles.get(name);
    }

    /** 离职: 移除角色并关闭其个人电脑. 返回是否移除成功. */
    public boolean removeRole(String roleId) {
        if (!roles.containsKey(roleId)) {
            return false;
        }
        AgentRole role = roles.remove(roleId);
        role.setRunning(false);
        futures.remove(roleId);
        // 离职: 销毁个人电脑 (走本系统电脑注册表, 不影响其他系统的同名角色)
        try {
            role.computerManager().destroy(roleId);
        } catch (Exception e) {
            logger.warn("[{}] 离职销毁电脑失败", roleId, e);
        }
        logger.info("Role '{}' removed (离职)", roleId);
        return true;
    }

    /** 返回所有角色列表 (按注册顺序). */
    public List<AgentRole> allRoles() {
        return new ArrayList<>(roles.values());
    }

    public List<String> listRoles() {
        return new ArrayList<>(roles.keySet());
    }

    /** 全局通知: 给每个角色的活动日志都写一条. */
    public void journalAll(String entry) {
        for (AgentRole role : roles.values()) {
            role.journal(entry);
        }
    }

    // ── Lifecycle ──────────────────────────────────────────

    /** 启动所有角色 worker 线程. */
    public void start() {
        for (AgentRole role : roles.values()) {
            if (autoToolkits) {
                setupRole(role);
            }
            startOneRole(role);
        }
    }

    /** 优雅停止所有角色 worker. */
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

    /** 将任务路由到指定角色的队列. */
    public void assignTask(String roleName, AgentRole.Task task) {
        getRole(roleName).addTask(task);
    }

    /** 所有角色状态快照. */
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

    /** 单个角色 worker 线程主循环. */
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
            role.journal("开始执行任务: " + truncate(task.description, 120));

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
                        throw new AgentRole.ToolLoopError("LLM 调用失败: " + truncate(resultText, 120));
                    }
                }
                task.result = resultText;
                task.tokensConsumed = tokens;
                task.status = AgentRole.STATUS_DONE;
                logger.info("[{}] Task {} done ({} tokens): {}", role.roleId, task.taskId, tokens,
                        truncate(resultText, 80));
                role.journal("任务完成 (" + tokens + " tokens): " + truncate(resultText, 150));
                role.appendTaskHistory(task);
            } catch (Exception exc) {
                task.result = "[ERROR] " + exc;
                task.status = AgentRole.STATUS_FAILED;
                logger.error("[{}] Task {} failed: {}", role.roleId, task.taskId, exc.toString());
                role.journal("任务失败: " + exc);
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

    // ── 访问器 ─────────────────────────────────────────────

    public TimeEventBus timeManager() {
        return timeManager;
    }

    /** 本池所属 AgentSystem (独立角色池为 null). */
    public AgentSystem owner() {
        return owner;
    }
}
