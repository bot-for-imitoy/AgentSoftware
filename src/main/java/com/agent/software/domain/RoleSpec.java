package com.agent.software.domain;

import com.agent.software.kernel.RoleId;

import java.util.List;
import java.util.Objects;

/**
 * Static definition of one employee: identity, persona, group, computer kind and
 * the toolkits it may use.
 *
 * <p>This is the sole description consumed by the runtime; the legacy
 * {@code AgentRole} mutable-field object is retired. Toolkit membership is data
 * (from {@code role_templates.json}), not a code branch.
 */
public record RoleSpec(
        RoleId id,
        String name,
        String username,
        int uid,
        String title,
        String responsibilities,
        String personality,
        List<String> skills,
        String systemPromptExtra,
        String group,
        String email,
        String computerKind,
        Payload computerKwargs,
        List<String> toolkits) {

    public RoleSpec {
        Objects.requireNonNull(id, "id");
        name = name == null || name.isBlank() ? id.value() : name.strip();
        username = username == null ? "" : username.strip();
        title = title == null ? "" : title;
        responsibilities = responsibilities == null ? "" : responsibilities;
        personality = personality == null ? "" : personality;
        skills = skills == null ? List.of() : List.copyOf(skills);
        systemPromptExtra = systemPromptExtra == null ? "" : systemPromptExtra;
        group = group == null ? "" : group.strip();
        email = email == null ? "" : email.strip();
        computerKind = computerKind == null || computerKind.isBlank() ? "podman" : computerKind.strip();
        computerKwargs = computerKwargs == null ? Payload.empty() : computerKwargs;
        toolkits = toolkits == null ? List.of() : List.copyOf(toolkits);
    }

    public boolean hasGroup() {
        return !group.isEmpty();
    }

    public RoleSpec withToolkits(List<String> newToolkits) {
        return new RoleSpec(id, name, username, uid, title, responsibilities, personality,
                skills, systemPromptExtra, group, email, computerKind, computerKwargs, newToolkits);
    }
}
