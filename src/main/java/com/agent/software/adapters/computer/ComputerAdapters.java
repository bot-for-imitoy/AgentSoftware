package com.agent.software.adapters.computer;

import com.agent.software.computers.Computer;
import com.agent.software.computers.ComputerManager;
import com.agent.software.domain.RoleSpec;
import com.agent.software.ports.ComputerPort;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates a {@link ComputerPort} for a role from its {@link RoleSpec}.
 *
 * <p>The {@link ComputerManager} is injected per application instance, so two
 * applications in one JVM keep separate registries; podman computers also bind
 * their owning registry so network/image are instance-scoped.
 */
public final class ComputerAdapters {

    private ComputerAdapters() {
    }

    /** Allocate (but do not power on) the role's computer and wrap it. */
    public static ComputerPort open(ComputerManager manager, RoleSpec spec) {
        Map<String, Object> kwargs = new LinkedHashMap<>(spec.computerKwargs().asMap());
        if (!spec.username().isBlank()) {
            kwargs.put("username", spec.username());
        }
        if (spec.uid() > 0) {
            kwargs.put("uid", spec.uid());
        }
        Computer computer = manager.create(spec.computerKind(), spec.id().value(), spec.name(), true, kwargs);
        return new LegacyComputerAdapter(computer);
    }

    /** Wrap an already-created computer. */
    public static ComputerPort wrap(Computer computer) {
        return new LegacyComputerAdapter(computer);
    }
}
