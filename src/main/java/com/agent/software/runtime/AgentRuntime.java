package com.agent.software.runtime;

import com.agent.software.domain.AgentState;
import com.agent.software.domain.Priority;
import com.agent.software.domain.RoleSpec;
import com.agent.software.domain.Task;
import com.agent.software.domain.TaskStatus;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.LlmPort;
import com.agent.software.ports.ToolPort;
import com.agent.software.ports.TracePort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * One role's resident worker.
 *
 * <p>Replaces the legacy {@code AgentRole + RolePool.roleLoop} pair. It owns a
 * priority {@link TaskQueue}, the {@link AgentState} machine, the {@link ToolLoop}
 * and a {@link WaitCoordinator}; it depends only on ports, never on a global
 * system object. Each instance runs on its own virtual thread.
 */
public final class AgentRuntime {

    /** Read-only status snapshot for the UI / team queries. */
    public record Snapshot(RoleId id, String name, AgentState state, boolean busy,
                           int queueDepth, String currentTask) {
    }

    private final RoleSpec spec;
    private final TracePort trace;
    private final Function<RoleSpec, String> promptFactory;
    private final ToolLoop toolLoop;
    private final TaskQueue queue = new TaskQueue();
    private final WaitCoordinator waits = new WaitCoordinator();
    private final List<Task> history = Collections.synchronizedList(new ArrayList<>());

    private volatile AgentState state = AgentState.IDLE;
    private volatile Task current;
    private volatile boolean running;
    private Thread worker;

    public AgentRuntime(RoleSpec spec, LlmPort llm, ToolPort tools, TracePort trace,
                        Function<RoleSpec, String> promptFactory, ToolLoop.Policy policy) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        this.spec = spec;
        this.trace = trace;
        this.promptFactory = promptFactory == null
                ? s -> "You are " + s.name() + ", working as the " + s.id() + " role."
                : promptFactory;
        this.toolLoop = new ToolLoop(llm, tools, trace, policy);
    }

    public RoleId id() {
        return spec.id();
    }

    public RoleSpec spec() {
        return spec;
    }

    public WaitCoordinator waits() {
        return waits;
    }

    public ToolLoop toolLoop() {
        return toolLoop;
    }

    // ── lifecycle ──────────────────────────────────────────────────────

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = Thread.ofVirtual().name("agent-" + id()).unstarted(this::loop);
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        waits.end();
        Thread w = worker;
        worker = null;
        if (w != null) {
            w.interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    // ── queue / state ──────────────────────────────────────────────────

    /** Enqueue a task for this role and mark it assigned. */
    public void submit(Task task) {
        task.assignTo(id().value());
        queue.push(task);
    }

    public AgentState state() {
        return state;
    }

    /** Set the lifecycle state (driven by the lifecycle coordinator / summary tool). */
    public void setState(AgentState newState) {
        this.state = newState;
    }

    public boolean isBusy() {
        return current != null;
    }

    public int queueDepth() {
        return queue.size();
    }

    public Task currentTask() {
        return current;
    }

    public List<Task> pendingTasks() {
        return queue.snapshot();
    }

    public List<Task> history(int limit) {
        synchronized (history) {
            if (limit <= 0 || limit >= history.size()) {
                return new ArrayList<>(history);
            }
            return new ArrayList<>(history.subList(history.size() - limit, history.size()));
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(id(), spec.name(), state, isBusy(), queue.size(),
                current == null ? null : current.description());
    }

    /** Restore queued tasks from a snapshot (assigns them to this role). */
    public void restorePending(List<Task> tasks) {
        if (tasks == null) {
            return;
        }
        for (Task task : tasks) {
            submit(task);
        }
    }

    /** Replace the completed-task history from a snapshot. */
    public void restoreHistory(List<Task> completed) {
        synchronized (history) {
            history.clear();
            if (completed != null) {
                history.addAll(completed);
            }
        }
    }

    /** Wait until the queue is empty, no task is running and the role is not waiting. */
    public boolean awaitIdle(Duration timeout) {
        long deadline = timeout == null ? Long.MAX_VALUE
                : System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (queue.isEmpty() && current == null && state != AgentState.WAITING) {
                return true;
            }
            sleep(20);
        }
        return queue.isEmpty() && current == null && state != AgentState.WAITING;
    }

    // ── worker ─────────────────────────────────────────────────────────

    private void loop() {
        while (running) {
            Optional<Task> next = queue.peek();
            if (next.isEmpty()) {
                sleep(50);
                continue;
            }
            if (isHeld(next.get())) {
                sleep(50);
                continue;
            }
            Task task = queue.pop().orElse(null);
            if (task != null) {
                runTask(task);
            }
        }
    }

    private boolean isHeld(Task task) {
        if (state == AgentState.WAITING) {
            return true;
        }
        boolean offShift = state == AgentState.OFF_DUTY || state == AgentState.WRAPPING_UP;
        return offShift && task.urgency() < Priority.EMERGENCY.value();
    }

    private void runTask(Task task) {
        current = task;
        state = AgentState.BUSY;
        task.markRunning();
        int tokens = 0;
        try {
            String prompt = promptFactory.apply(spec);
            ToolLoop.Outcome outcome = toolLoop.run(id(), prompt, task.description());
            tokens = outcome.tokens();
            task.markDone(outcome.answer(), tokens);
            trace.answer(id(), task.id(), TaskStatus.DONE, tokens, outcome.answer());
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            task.markFailed("[ERROR] " + message, tokens);
            trace.answer(id(), task.id(), TaskStatus.FAILED, tokens, task.result());
        } finally {
            history.add(task);
            current = null;
            if (state == AgentState.BUSY) {
                state = AgentState.IDLE;
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
