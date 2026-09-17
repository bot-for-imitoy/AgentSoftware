package com.agent.software.agent;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.tool.computer.Shell;
import com.agent.software.tool.spi.Toolbox;

import java.util.List;
import java.util.Optional;
import com.agent.software.agent.dialog.ConversationMemory;
import com.agent.software.agent.dialog.SystemPrompt;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.agent.task.Task;
import com.agent.software.agent.task.ToolLoop;

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
public final class Agent implements AgentTasks, AgentControl {

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
    private Thread worker;

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
        throw new UnsupportedOperationException("skeleton");
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
        throw new UnsupportedOperationException("skeleton");
    }

    public synchronized void stop() {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean isRunning() {
        throw new UnsupportedOperationException("skeleton");
    }

    // ── 队列 ───────────────────────────────────────────────────

    /** 投递任务；deferred=true 进入暂存队列（等状态允许再提升）。 */
    public void submit(Task task, boolean deferred) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 把暂存任务提升为可执行（ShiftDirector 在上班时调用）。 */
    public void promoteDeferred() {
        throw new UnsupportedOperationException("skeleton");
    }

    public AgentState state() {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean busy() {
        throw new UnsupportedOperationException("skeleton");
    }

    public int queueDepth() {
        throw new UnsupportedOperationException("skeleton");
    }

    public Optional<Task> currentTask() {
        throw new UnsupportedOperationException("skeleton");
    }

    public List<Task> pendingTasks() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Task> history(int limit) {
        throw new UnsupportedOperationException("skeleton");
    }

    public AgentSnapshot snapshot() {
        throw new UnsupportedOperationException("skeleton");
    }

    // ── TaskRunner 内部回调 ────────────────────────────────────

    /** 开始执行某任务。 */
    public void beginTask(Task task) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 任务结束：写入历史、清空当前、状态回到 IDLE。 */
    public void finishTask(Task task) {
        throw new UnsupportedOperationException("skeleton");
    }

    // ── AgentTasks ─────────────────────────────────────────────

    @Override
    public List<Task> pending() {
        throw new UnsupportedOperationException("skeleton");
    }

    // ── AgentControl ───────────────────────────────────────────

    @Override
    public void transitionTo(AgentState next) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void closeDayConversation(int day) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void powerOffComputer() {
        throw new UnsupportedOperationException("skeleton");
    }
}
