package com.agent.software.adapters.persistence;

import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.AgentException;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.StateRepository;
import com.agent.software.kernel.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JSON {@link StateRepository} with atomic writes.
 *
 * <p>Stores only data (role specs, task snapshots, clock position); rebuilding the
 * runtime from a snapshot is the composition root's job.
 */
public final class JsonStateRepository implements StateRepository {

    private static final int VERSION = 1;

    private final Path file;

    public JsonStateRepository(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Snapshot> load() {
        if (file == null || !Files.exists(file)) {
            return Optional.empty();
        }
        try {
            Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> root)) {
                return Optional.empty();
            }
            Map<String, Object> rootMap = (Map<String, Object>) root;
            if (intOf(rootMap.get("version"), 0) != VERSION) {
                return Optional.empty();
            }
            Map<String, Object> time = mapOf(rootMap.get("time"));
            int day = intOf(time.get("day"), 1);
            int tickOfDay = intOf(time.get("tick_of_day"), 0);
            String baseDate = str(time.get("base_date"));

            List<RoleState> roles = new ArrayList<>();
            if (rootMap.get("roles") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> roleMap) {
                        roles.add(roleState((Map<String, Object>) roleMap));
                    }
                }
            }
            return Optional.of(new Snapshot(day, tickOfDay, baseDate, roles));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public void save(Snapshot snapshot) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("saved_at", LocalDateTime.now().toString());

        Map<String, Object> time = new LinkedHashMap<>();
        time.put("day", snapshot.day());
        time.put("tick_of_day", snapshot.tickOfDay());
        time.put("base_date", snapshot.baseDate());
        root.put("time", time);

        List<Map<String, Object>> roles = new ArrayList<>();
        for (RoleState role : snapshot.roles()) {
            roles.add(roleMap(role));
        }
        root.put("roles", roles);

        try {
            Json.atomicWrite(file, Json.stringifyPretty(root));
        } catch (IOException e) {
            throw new AgentException.PortException("cannot save state: " + file, e);
        }
    }

    // ── mapping ────────────────────────────────────────────────────────

    private static Map<String, Object> roleMap(RoleState role) {
        RoleSpec spec = role.spec();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role_id", spec.id().value());
        m.put("name", spec.name());
        m.put("username", spec.username());
        m.put("uid", spec.uid());
        m.put("title", spec.title());
        m.put("responsibilities", spec.responsibilities());
        m.put("personality", spec.personality());
        m.put("skills", spec.skills());
        m.put("system_prompt_extra", spec.systemPromptExtra());
        m.put("group", spec.group());
        m.put("email", spec.email());
        m.put("computer_kind", spec.computerKind());
        m.put("computer_kwargs", spec.computerKwargs().asMap());
        m.put("toolkits", spec.toolkits());
        m.put("state", role.state());
        m.put("pending_tasks", role.pending().stream().map(JsonStateRepository::taskMap).toList());
        m.put("history", role.history().stream().map(JsonStateRepository::taskMap).toList());
        return m;
    }

    private static RoleState roleState(Map<String, Object> m) {
        RoleSpec spec = new RoleSpec(
                RoleId.of(str(m.get("role_id"))),
                str(m.get("name")),
                str(m.get("username")),
                intOf(m.get("uid"), 0),
                str(m.get("title")),
                str(m.get("responsibilities")),
                str(m.get("personality")),
                strList(m.get("skills")),
                str(m.get("system_prompt_extra")),
                str(m.get("group")),
                str(m.get("email")),
                str(m.get("computer_kind")),
                Payload.of(mapOf(m.get("computer_kwargs"))),
                strList(m.get("toolkits")));
        return new RoleState(spec, str(m.get("state")), tasks(m.get("pending_tasks")), tasks(m.get("history")));
    }

    private static Map<String, Object> taskMap(TaskSnapshot t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id", t.id());
        m.put("urgency", t.urgency());
        m.put("description", t.description());
        m.put("source", t.source());
        m.put("status", t.status());
        m.put("result", t.result());
        m.put("tokens_consumed", t.tokens());
        m.put("created_at", t.createdAt());
        m.put("context", t.context().asMap());
        return m;
    }

    private static List<TaskSnapshot> tasks(Object raw) {
        List<TaskSnapshot> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) map;
            out.add(new TaskSnapshot(
                    str(m.get("task_id")),
                    intOf(m.get("urgency"), 3),
                    str(m.get("description")),
                    str(m.get("source")),
                    Payload.of(mapOf(m.get("context"))),
                    str(m.get("status")),
                    str(m.get("result")),
                    intOf(m.get("tokens_consumed"), 0),
                    doubleOf(m.get("created_at"), 0)));
        }
        return out;
    }

    // ── primitive helpers ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return (Map<String, Object>) map;
    }

    private static String str(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static int intOf(Object raw, int def) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static double doubleOf(Object raw, double def) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        return def;
    }

    private static List<String> strList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}
