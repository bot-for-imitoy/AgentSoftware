package com.agent.software.runtime;

import com.agent.software.domain.AgentState;
import com.agent.software.domain.RoleSpec;
import com.agent.software.domain.Task;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.TeamPort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry and lifecycle owner for all role workers of one application instance.
 *
 * <p>Replaces the legacy {@code RolePool} and the role-management half of
 * {@code AgentSystem}. It is instance-scoped: two {@code TeamRuntime}s in one JVM
 * never share state.
 */
public final class TeamRuntime implements TeamPort {

    /** Creates an {@link AgentRuntime} for a spec; supplied by the composition root. */
    @FunctionalInterface
    public interface AgentFactory {
        AgentRuntime create(RoleSpec spec);
    }

    private final Map<RoleId, AgentRuntime> roles = new LinkedHashMap<>();
    private final AgentFactory factory;

    public TeamRuntime(AgentFactory factory) {
        this.factory = factory;
    }

    /** Register a pre-built runtime without starting it (used by tests and restore). */
    public AgentRuntime register(RoleSpec spec, AgentRuntime runtime) {
        roles.put(spec.id(), runtime);
        return runtime;
    }

    /** Create, register and immediately start a new role (the hiring path). */
    public AgentRuntime hire(RoleSpec spec) {
        AgentRuntime runtime = factory.create(spec);
        register(spec, runtime);
        runtime.start();
        return runtime;
    }

    /** Stop and remove a role (the resignation path). */
    public boolean fire(RoleId id) {
        AgentRuntime runtime = roles.remove(id);
        if (runtime == null) {
            return false;
        }
        runtime.stop();
        return true;
    }

    public Optional<AgentRuntime> find(RoleId id) {
        return Optional.ofNullable(roles.get(id));
    }

    public boolean contains(RoleId id) {
        return roles.containsKey(id);
    }

    public List<AgentRuntime> all() {
        return new ArrayList<>(roles.values());
    }

    public List<RoleId> ids() {
        return new ArrayList<>(roles.keySet());
    }

    public int size() {
        return roles.size();
    }

    public List<AgentRuntime.Snapshot> roster() {
        List<AgentRuntime.Snapshot> out = new ArrayList<>(roles.size());
        for (AgentRuntime r : roles.values()) {
            out.add(r.snapshot());
        }
        return out;
    }

    public void startAll() {
        for (AgentRuntime r : roles.values()) {
            r.start();
        }
    }

    public void stopAll() {
        for (AgentRuntime r : roles.values()) {
            r.stop();
        }
    }

    public boolean allIdle() {
        if (roles.isEmpty()) {
            return false;
        }
        for (AgentRuntime r : roles.values()) {
            if (r.isBusy() || r.queueDepth() > 0) {
                return false;
            }
        }
        return true;
    }

    // ── TeamPort ───────────────────────────────────────────────────────

    @Override
    public List<RoleSpec> members() {
        List<RoleSpec> out = new ArrayList<>(roles.size());
        for (AgentRuntime r : roles.values()) {
            out.add(r.spec());
        }
        return out;
    }

    @Override
    public Optional<RoleSpec> spec(RoleId id) {
        return find(id).map(AgentRuntime::spec);
    }

    @Override
    public Optional<RoleId> resolve(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return Optional.empty();
        }
        String needle = nameOrId.strip();
        RoleId direct = RoleId.of(needle);
        if (roles.containsKey(direct)) {
            return Optional.of(direct);
        }
        for (AgentRuntime r : roles.values()) {
            if (r.spec().name().equalsIgnoreCase(needle)) {
                return Optional.of(r.id());
            }
        }
        return Optional.empty();
    }

    @Override
    public AgentState stateOf(RoleId id) {
        AgentRuntime runtime = roles.get(id);
        return runtime == null ? AgentState.OFF_DUTY : runtime.state();
    }

    @Override
    public Optional<String> waitingReplyFrom(RoleId id) {
        AgentRuntime runtime = roles.get(id);
        if (runtime == null || !runtime.waits().isWaiting()) {
            return Optional.empty();
        }
        return Optional.ofNullable(runtime.waits().waitingFor());
    }

    @Override
    public boolean deliverReply(RoleId target, RoleId from, String message) {
        AgentRuntime runtime = roles.get(target);
        if (runtime == null || runtime.state() != AgentState.WAITING) {
            return false;
        }
        String waitingFor = runtime.waits().waitingFor();
        if (waitingFor == null || !waitingFor.equals(from.value())) {
            return false;
        }
        return runtime.waits().deliver(message);
    }

    @Override
    public void enqueue(RoleId target, Task task) {
        AgentRuntime runtime = roles.get(target);
        if (runtime != null) {
            runtime.submit(task);
        }
    }

    @Override
    public Optional<String> waitForReply(RoleId self, RoleId target, Task task, Duration timeout) {
        AgentRuntime selfRuntime = roles.get(self);
        AgentRuntime targetRuntime = roles.get(target);
        if (selfRuntime == null || targetRuntime == null) {
            return Optional.empty();
        }
        AgentState before = selfRuntime.state();
        selfRuntime.waits().begin(target.value());
        selfRuntime.setState(AgentState.WAITING);
        try {
            targetRuntime.submit(task);
            return selfRuntime.waits().await(timeout);
        } finally {
            selfRuntime.waits().end();
            if (selfRuntime.state() == AgentState.WAITING) {
                selfRuntime.setState(before == AgentState.WAITING ? AgentState.IDLE : before);
            }
        }
    }

    @Override
    public void setState(RoleId id, AgentState state) {
        AgentRuntime runtime = roles.get(id);
        if (runtime != null) {
            runtime.setState(state);
        }
    }

    @Override
    public List<Task> pendingTasks(RoleId id) {
        AgentRuntime runtime = roles.get(id);
        return runtime == null ? List.of() : runtime.pendingTasks();
    }

    @Override
    public List<Task> taskHistory(RoleId id, int limit) {
        AgentRuntime runtime = roles.get(id);
        return runtime == null ? List.of() : runtime.history(limit);
    }
}
