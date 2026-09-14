package com.agent.software.runtime;

import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.RoleId;

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
public final class TeamRuntime {

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
}
