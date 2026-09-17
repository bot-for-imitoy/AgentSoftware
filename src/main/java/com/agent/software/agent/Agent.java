package com.agent.software.agent;

import com.agent.software.agent.dialog.ConversationMemory;
import com.agent.software.agent.dialog.SystemPrompt;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.agent.task.Task;
import com.agent.software.agent.task.TaskRunner;
import com.agent.software.agent.task.ToolLoop;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.tool.computer.Shell;
import com.agent.software.tool.spi.Toolbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 一个角色的门面：只做组合，不含业务实现。
 *
 * <p>它把 master {@code AgentRole} 的六个职责分别委托出去：
 * {@link AgentMailbox}（队列）、{@link AgentStateMachine}（状态）、
 * {@link WaitCoordinator}（等待）、{@link ConversationMemory}（对话）、
 * {@link Toolbox}（工具，由 start() 经 {@link ToolboxFactory} 装配）、
 * {@link TaskRunner}（线程体，由 start() 创建但不由本类持有）。
 *
 * <p>对外只暴露窄接口：工具拿到的"自身"能力是 {@link AgentTasks} + {@link AgentControl}。
 */
public final class Agent implements AgentRuntime {

    private static final Logger logger = LoggerFactory.getLogger(Agent.class);

    /** 历史任务保留上限（防止长跑进程把内存吃掉）。 */
    private static final int HISTORY_LIMIT = 200;

    private final RoleSpec spec;
    private final Shell shell;
    private final AgentMailbox mailbox;
    private final AgentStateMachine state;
    private final WaitCoordinator waits;
    private final ToolboxFactory toolboxFactory;
    private Toolbox toolbox;
    private final ConversationMemory conversation;
    private final ToolLoop toolLoop;
    private final SystemPrompt prompts;
    private final LifecycleGate gate;

    private final java.util.List<Task> history = new java.util.ArrayList<>();
    private volatile Task current;
    private volatile boolean running;
    private volatile Thread worker;
    private volatile TaskRunner runner;

    public Agent(RoleSpec spec, Shell shell, AgentMailbox mailbox, AgentStateMachine state,
                 WaitCoordinator waits, ToolboxFactory toolboxFactory, ConversationMemory conversation,
                 ToolLoop toolLoop, SystemPrompt prompts, LifecycleGate gate) {
        this.spec = spec;
        this.shell = shell;
        this.mailbox = mailbox;
        this.state = state;
        this.waits = waits;
        this.toolboxFactory = toolboxFactory;
        this.conversation = conversation;
        this.toolLoop = toolLoop;
        this.prompts = prompts;
        this.gate = gate;
    }

    // ── 身份与协作者（engine 内部使用） ────────────────────────

    public RoleId id() {
        return spec.id();
    }

    public RoleSpec spec() {
        return spec;
    }

    public Shell shell() {
        return shell;
    }

    public AgentMailbox mailbox() {
        return mailbox;
    }

    public AgentStateMachine stateMachine() {
        return state;
    }

    public WaitCoordinator waits() {
        return waits;
    }

    public Toolbox toolbox() {
        return toolbox;
    }

    public ConversationMemory conversation() {
        return conversation;
    }

    public ToolLoop toolLoop() {
        return toolLoop;
    }

    public SystemPrompt prompts() {
        return prompts;
    }

    // ── 生命周期 ───────────────────────────────────────────────

    /** 装配工具箱、拉起 worker。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        this.toolbox = toolboxFactory.create(spec, this, this);
        this.runner = new TaskRunner(mailbox, this, toolLoop, toolLoop.transcript(), gate);
        this.running = true;
        this.worker = Thread.ofVirtual()
                .name("agent-" + spec.id().value())
                .unstarted(runner);
        this.worker.start();
        logger.info("[{}] worker 已启动（工具 {} 个）", spec.id().value(),
                toolbox == null ? 0 : toolbox.specs().size());
    }

    public synchronized void stop() {
        running = false;
        TaskRunner r = runner;
        if (r != null) {
            r.requestStop();
        }
        Thread w = worker;
        if (w != null) {
            w.interrupt();
            try {
                w.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        logger.info("[{}] worker 已停止", spec.id().value());
    }

    public boolean isRunning() {
        return running;
    }

    // ── 队列 ───────────────────────────────────────────────────

    /** 投递任务；deferred=true 进入暂存队列（等状态允许再提升）。 */
    public void submit(Task task, boolean deferred) {
        if (task == null) {
            return;
        }
        mailbox.push(task, deferred);
        logger.debug("[{}] 任务入队（{}）：{}", spec.id().value(),
                deferred ? "暂存" : "可执行", task.description());
    }

    /** 把暂存任务提升为可执行（ShiftDirector 在上班时调用）。 */
    public void promoteDeferred() {
        mailbox.promoteDeferred();
    }

    public AgentState state() {
        return state.state();
    }

    public boolean busy() {
        return current != null;
    }

    public int queueDepth() {
        return mailbox.readyDepth() + mailbox.deferredDepth();
    }

    public Optional<Task> currentTask() {
        return Optional.ofNullable(current);
    }

    public List<Task> pendingTasks() {
        return mailbox.snapshot();
    }

    @Override
    public List<Task> history(int limit) {
        synchronized (history) {
            if (limit <= 0 || limit >= history.size()) {
                return new ArrayList<>(history);
            }
            return new ArrayList<>(history.subList(history.size() - limit, history.size()));
        }
    }

    public AgentSnapshot snapshot() {
        Task task = current;
        return new AgentSnapshot(spec.id(), spec.name(), state.state(), busy(), queueDepth(),
                task == null ? "" : task.description());
    }

    // ── TaskRunner 内部回调 ────────────────────────────────────

    /** 开始执行某任务。 */
    public void beginTask(Task task) {
        this.current = task;
        try {
            state.to(AgentState.ON_DUTY_BUSY);
        } catch (RuntimeException e) {
            logger.debug("[{}] 开始任务时状态迁移被拒绝：{}", spec.id().value(), e.getMessage());
        }
    }

    /** 任务结束：写入历史、清空当前、状态回到 IDLE。 */
    public void finishTask(Task task) {
        this.current = null;
        if (task != null) {
            synchronized (history) {
                history.add(task);
                while (history.size() > HISTORY_LIMIT) {
                    history.remove(0);
                }
            }
        }
        // 只在"忙碌"时回到空闲。任务执行期间工具可能已经把角色改成别的状态：
        // summary 工具会写成 OFF_DUTY、talk wait=true 会写 WAITING——这些都不能被
        // "任务结束"覆盖掉，否则下班状态会在收尾任务的最后一步被抹掉（真实踩过的坑）。
        if (state.state() == AgentState.ON_DUTY_BUSY) {
            try {
                state.toIdle();
            } catch (RuntimeException e) {
                logger.debug("[{}] 结束任务时状态迁移被拒绝：{}", spec.id().value(), e.getMessage());
            }
        }
        // talk wait=true 的回复通道：委派任务结束时把结果投回等待者
        if (task != null) {
            task.notifyComplete(task.result());
        }
    }

    // ── AgentTasks ─────────────────────────────────────────────

    @Override
    public List<Task> pending() {
        return mailbox.snapshot();
    }

    // ── AgentRuntime ───────────────────────────────────────────

    @Override
    public String systemPrompt() {
        return prompts.build(spec);
    }

    @Override
    public int currentDay() {
        return prompts.clock().nowDay().day();
    }

    // ── AgentControl ───────────────────────────────────────────

    @Override
    public void transitionTo(AgentState next) {
        state.to(next);
    }

    @Override
    public void closeDayConversation(int day) {
        conversation.closeDay(day);
    }

    @Override
    public void powerOffComputer() {
        if (shell == null) {
            return;
        }
        try {
            shell.powerOff();
        } catch (RuntimeException e) {
            logger.warn("[{}] 关闭个人电脑失败：{}", spec.id().value(), e.getMessage());
        }
    }

    // ── 读档 ───────────────────────────────────────────────────

    /** 从快照恢复状态 / 队列 / 历史 / 当日对话（{@code agent.Staffing#restore} 调用）。 */
    public void restore(RoleSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        state.restore(snapshot.state());
        mailbox.clear();
        for (Task.TaskRecord record : snapshot.pending()) {
            mailbox.push(Task.fromRecord(record), false);
        }
        synchronized (history) {
            history.clear();
            for (Task.TaskRecord record : snapshot.history()) {
                history.add(Task.fromRecord(record));
            }
        }
        conversation.restore(new ConversationMemory.State(
                snapshot.conversationDay(), -1, snapshot.conversation()));
    }
}
