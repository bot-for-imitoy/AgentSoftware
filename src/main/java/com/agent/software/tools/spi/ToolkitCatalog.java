package com.agent.software.tools.spi;

import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.AgentException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of all known toolkits. A role's toolkit list comes from its
 * {@code RoleSpec} (declared in {@code role_templates.json}); an unknown id is a
 * configuration error rather than a silent omission.
 */
public final class ToolkitCatalog {

    private final Map<String, Toolkit> byId = new LinkedHashMap<>();

    public ToolkitCatalog register(Toolkit toolkit) {
        byId.put(toolkit.id(), toolkit);
        return this;
    }

    public Optional<Toolkit> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<String> ids() {
        return new ArrayList<>(byId.keySet());
    }

    public int size() {
        return byId.size();
    }

    /** Toolkits requested by a role spec, in declaration order. */
    public List<Toolkit> forSpec(RoleSpec spec) {
        List<Toolkit> out = new ArrayList<>();
        for (String id : spec.toolkits()) {
            Toolkit toolkit = byId.get(id);
            if (toolkit == null) {
                throw new AgentException.ConfigException(
                        "role '" + spec.id() + "' requests unknown toolkit '" + id
                                + "'; known toolkits: " + byId.keySet());
            }
            out.add(toolkit);
        }
        return out;
    }
}
