package com.agent.software.app;

import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.AgentException;
import com.agent.software.kernel.RoleId;
import com.agent.software.utils.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads {@link RoleSpec}s from {@code role_templates.json}.
 *
 * <p>Toolkits are data-driven: a template may declare an explicit {@code toolkits}
 * array; when it does not, the configured defaults apply plus {@code client} for
 * Leadership Group members and {@code hr} for the HR role. No code branch decides
 * what a role may do beyond this defaulting rule.
 */
public final class RoleSpecLoader {

    public static final String DEFAULT_RESOURCE = "role_templates.json";
    public static final String LEADERSHIP_GROUP = "Leadership Group";

    private final Map<String, Map<String, Object>> templates;
    private final List<String> defaultToolkits;

    public RoleSpecLoader(Map<String, Map<String, Object>> templates, List<String> defaultToolkits) {
        this.templates = Map.copyOf(templates);
        this.defaultToolkits = defaultToolkits == null ? List.of() : List.copyOf(defaultToolkits);
    }

    public static RoleSpecLoader fromClasspath(String resource, List<String> defaultToolkits) {
        String name = resource == null || resource.isBlank() ? DEFAULT_RESOURCE : resource;
        try (InputStream in = RoleSpecLoader.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new AgentException.ConfigException("role template resource not found: " + name);
            }
            return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), defaultToolkits);
        } catch (IOException e) {
            throw new AgentException.ConfigException("cannot read role template resource: " + name, e);
        }
    }

    public static RoleSpecLoader fromClasspath(List<String> defaultToolkits) {
        return fromClasspath(DEFAULT_RESOURCE, defaultToolkits);
    }

    @SuppressWarnings("unchecked")
    public static RoleSpecLoader fromJson(String json, List<String> defaultToolkits) {
        Object root;
        try {
            root = Json.parse(json);
        } catch (IOException e) {
            throw new AgentException.ConfigException("role templates are not valid JSON", e);
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (root instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> conf) {
                    String id = String.valueOf(entry.getKey());
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) conf);
                    copy.putIfAbsent("role_id", id);
                    out.put(id, copy);
                }
            }
        } else if (root instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> conf) {
                    String id = Json.str((Map<String, Object>) conf, "role_id", "");
                    if (!id.isBlank()) {
                        out.put(id, new LinkedHashMap<>((Map<String, Object>) conf));
                    }
                }
            }
        } else {
            throw new AgentException.ConfigException("role templates root must be an object or array");
        }
        if (out.isEmpty()) {
            throw new AgentException.ConfigException("role templates contain no roles");
        }
        return new RoleSpecLoader(out, defaultToolkits);
    }

    public List<String> ids() {
        return new ArrayList<>(templates.keySet());
    }

    public Optional<RoleSpec> find(String id) {
        Map<String, Object> conf = templates.get(id);
        return conf == null ? Optional.empty() : Optional.of(toSpec(id, conf));
    }

    public RoleSpec require(String id) {
        return find(id).orElseThrow(() -> new AgentException.ConfigException(
                "unknown role template '" + id + "'; known: " + templates.keySet()));
    }

    public List<RoleSpec> all() {
        List<RoleSpec> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : templates.entrySet()) {
            out.add(toSpec(e.getKey(), e.getValue()));
        }
        return out;
    }

    public List<RoleSpec> many(List<String> ids) {
        List<RoleSpec> out = new ArrayList<>();
        for (String id : ids) {
            out.add(require(id));
        }
        return out;
    }

    public List<String> defaultToolkits() {
        return defaultToolkits;
    }

    private RoleSpec toSpec(String id, Map<String, Object> conf) {
        String group = Json.str(conf, "group", "");
        List<String> toolkits = new ArrayList<>(Json.strList(conf, "toolkits"));
        if (toolkits.isEmpty()) {
            LinkedHashSet<String> resolved = new LinkedHashSet<>(defaultToolkits);
            if (LEADERSHIP_GROUP.equals(group)) {
                resolved.add("client");
            }
            if ("HR".equals(id)) {
                resolved.add("hr");
            }
            toolkits = new ArrayList<>(resolved);
        }
        return new RoleSpec(
                RoleId.of(id),
                Json.str(conf, "name", id),
                Json.str(conf, "username", ""),
                Json.intVal(conf, "uid", 0),
                Json.str(conf, "title", ""),
                Json.str(conf, "responsibilities", ""),
                Json.str(conf, "personality", ""),
                Json.strList(conf, "skills"),
                Json.str(conf, "system_prompt_extra", ""),
                group,
                Json.str(conf, "email", ""),
                Json.str(conf, "computer_kind", ""),
                computerKwargs(conf),
                toolkits);
    }

    private static Payload computerKwargs(Map<String, Object> conf) {
        if (!(conf.get("computer_kwargs") instanceof Map<?, ?> raw)) {
            return Payload.empty();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return Payload.of(out);
    }
}
