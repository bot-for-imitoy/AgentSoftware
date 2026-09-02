package com.agent.software.store;

import com.agent.software.AgentSystem;
import com.agent.software.computers.Computer;
import com.agent.software.core.Types;

import com.agent.software.event.TimeEventBus;
import com.agent.software.role.AgentRole;
import com.agent.software.role.RolePool;
import com.agent.software.utils.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Unified state storage (StateStore) — persistence of structured data + conversation content + container info
 * (Python version state_store.py).
 *
 * Aggregates all serializable state into a single JSON file (default data/state.json):
 * role profiles / task history / pending tasks / computer container info / time progress.
 */
public class StateStore {

    private static final Logger logger = LoggerFactory.getLogger(StateStore.class);

    public static final String DEFAULT_STATE_FILE = "./data/state.json";
    public static final int VERSION = 1;

    private final Path path;

    public StateStore(String path) {
        this.path = Paths.get(path != null ? path : DEFAULT_STATE_FILE);
    }

    public StateStore() {
        this(DEFAULT_STATE_FILE);
    }

    public boolean exists() {
        return Files.exists(path);
    }

    /** Save the entire system state to JSON (atomic write). Returns the archive file path. */
    public String save(AgentSystem system) {
        Map<String, Object> data = collect(system);
        try {
            Json.atomicWrite(path, Json.stringifyPretty(data));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save state: " + path, e);
        }
        int historyCount = 0;
        for (Map<String, Object> r : (List<Map<String, Object>>) data.get("roles")) {
            historyCount += ((List<?>) r.get("history")).size();
        }
        logger.info("StateStore: state saved → {} (roles {}, task history {})",
                path, ((List<?>) data.get("roles")).size(), historyCount);
        return path.toString();
    }

    /** Restore system state from the archive (role profiles/tasks/containers/time). Returns the number of restored roles. */
    public int restore(AgentSystem system) {
        if (!exists()) {
            return 0;
        }
        Map<String, Object> data;
        try {
            data = Json.parseObject(Files.readString(path));
        } catch (Exception e) {
            logger.error("StateStore: failed to read archive, skipping restore: {}", e.getMessage());
            return 0;
        }
        Object version = data.get("version");
        if (!Integer.valueOf(VERSION).equals(version instanceof Number n ? n.intValue() : version)) {
            logger.warn("StateStore: archive version {} != current {}, skipping restore", version, VERSION);
            return 0;
        }
        return apply(data, system);
    }

    // ── Collect (memory → Map) ───────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> collect(AgentSystem system) {
        RolePool pool = system.pool;
        TimeEventBus tm = system.timeManager;

        List<Map<String, Object>> rolesData = new ArrayList<>();
        for (AgentRole role : pool.allRoles()) {
            rolesData.add(roleToDict(role));
        }

        // computer/container info
        Map<String, Object> computers = new LinkedHashMap<>();
        for (AgentRole role : pool.allRoles()) {
            Computer comp = role.computerIfCreated();
            if (comp == null) {
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            String kind = comp.getClass().getSimpleName().replace("Computer", "").toLowerCase();
            c.put("kind", kind);
            c.put("auto_mcp", comp.isAutoMcp());
            c.put("name", system.computerManager.nameOf(role.roleId, role.name));
            computers.put(role.roleId, c);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", VERSION);
        data.put("saved_at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        Map<String, Object> time = new LinkedHashMap<>();
        time.put("day", tm.dayNumber());
        time.put("tick_of_day", tm.tickOfDay());
        data.put("time", time);
        data.put("roles", rolesData);
        data.put("computers", computers);
        return data;
    }

    /** Role profile + tasks (pending queue + history) → Map. */
    private Map<String, Object> roleToDict(AgentRole role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role_id", role.roleId);
        m.put("name", role.name);
        m.put("title", role.title);
        m.put("responsibilities", role.responsibilities);
        m.put("personality", role.personality);
        m.put("skills", new ArrayList<>(role.skills));
        List<String> keywords = new ArrayList<>(role.interestKeywords);
        keywords.sort(String::compareTo);
        m.put("interest_keywords", keywords);
        m.put("system_prompt_extra", role.systemPromptExtra);
        m.put("is_default", role.isDefault);
        m.put("state", role.state.value);
        m.put("salience_threshold", role.salienceThreshold);
        m.put("computer_kind", role.computerKind);
        m.put("computer_kwargs", new LinkedHashMap<>(role.computerKwargs));
        List<Map<String, Object>> pending = new ArrayList<>();
        for (AgentRole.Task t : role.pendingTasks()) {
            pending.add(t.toDict());
        }
        m.put("pending_tasks", pending);
        List<Map<String, Object>> history = new ArrayList<>();
        for (AgentRole.Task t : role.taskHistory(null)) {
            history.add(t.toDict());
        }
        m.put("history", history);
        return m;
    }

    // ── Apply (Map → memory) ───────────────────────────────

    @SuppressWarnings("unchecked")
    private int apply(Map<String, Object> data, AgentSystem system) {
        RolePool pool = system.pool;
        int restored = 0;

        // 1) Role profiles + tasks
        Object rolesObj = data.get("roles");
        if (rolesObj instanceof List) {
            for (Object rObj : (List<Object>) rolesObj) {
                Map<String, Object> rdata = (Map<String, Object>) rObj;
                AgentRole role = restoreRole(pool, rdata, system);
                if (role == null) {
                    continue;
                }
                restored++;
                Object pending = rdata.get("pending_tasks");
                if (pending instanceof List) {
                    for (Object t : (List<Object>) pending) {
                        role.addTask(AgentRole.Task.fromDict((Map<String, Object>) t));
                    }
                }
                List<AgentRole.Task> history = new ArrayList<>();
                Object hist = rdata.get("history");
                if (hist instanceof List) {
                    for (Object t : (List<Object>) hist) {
                        history.add(AgentRole.Task.fromDict((Map<String, Object>) t));
                    }
                }
                role.restoreTaskHistory(history);
            }
        }

        // 2) Computers/containers: rebuild objects and bind to existing containers (no container recreation)
        Object computersObj = data.get("computers");
        if (computersObj instanceof Map) {
            restoreComputers(system, (Map<String, Object>) computersObj);
        }

        // 3) Time progress (applied on start())
        Map<String, Object> t = (Map<String, Object>) data.getOrDefault("time", new LinkedHashMap<>());
        int day = t.get("day") instanceof Number n ? n.intValue() : 1;
        int tod = t.get("tick_of_day") instanceof Number n2 ? n2.intValue() : 0;
        system.timeManager.setProgress(day, tod);

        logger.info("StateStore: restored {} roles → {}", restored, system.timeManager.describe());
        return restored;
    }

    /** Restore a single role from the archive: overwrite fields if already registered, otherwise rebuild and wire it up. */
    @SuppressWarnings("unchecked")
    private AgentRole restoreRole(RolePool pool, Map<String, Object> rdata, AgentSystem system) {
        String roleId = Json.str(rdata, "role_id", "");
        AgentRole role = pool.getRoleOrNull(roleId);
        if (role == null) {
            // The role in the archive is not in the current template pool: rebuild and wire it up
            Set<String> keywords = new java.util.LinkedHashSet<>();
            for (String k : Json.strList(rdata, "interest_keywords")) {
                keywords.add(k);
            }
            role = AgentRole.builder()
                    .name(Json.str(rdata, "name", roleId))
                    .roleId(roleId)
                    .title(Json.str(rdata, "title", ""))
                    .responsibilities(Json.str(rdata, "responsibilities", ""))
                    .personality(Json.str(rdata, "personality", ""))
                    .skills(Json.strList(rdata, "skills"))
                    .interestKeywords(keywords)
                    .systemPromptExtra(Json.str(rdata, "system_prompt_extra", ""))
                    .isDefault(Json.boolVal(rdata, "is_default", false))
                    .computerKind(Json.str(rdata, "computer_kind", "podman"))
                    .computerKwargs((Map<String, Object>) rdata.getOrDefault(
                            "computer_kwargs", new LinkedHashMap<>()))
                    .build();
            system.addRole(role);
        }
        // Overwrite profile fields (roles created from templates follow the archive)
        if (rdata.containsKey("name")) {
            role.name = Json.str(rdata, "name", role.name);
        }
        if (rdata.containsKey("title")) {
            role.title = Json.str(rdata, "title", role.title);
        }
        if (rdata.containsKey("responsibilities")) {
            role.responsibilities = Json.str(rdata, "responsibilities", role.responsibilities);
        }
        if (rdata.containsKey("personality")) {
            role.personality = Json.str(rdata, "personality", role.personality);
        }
        if (rdata.containsKey("system_prompt_extra")) {
            role.systemPromptExtra = Json.str(rdata, "system_prompt_extra", role.systemPromptExtra);
        }
        role.skills = new ArrayList<>(Json.strList(rdata, "skills"));
        Set<String> keywords = new java.util.LinkedHashSet<>();
        for (String k : Json.strList(rdata, "interest_keywords")) {
            keywords.add(k);
        }
        if (!keywords.isEmpty()) {
            role.interestKeywords = keywords;
        }
        double threshold = Json.doubleVal(rdata, "salience_threshold", -1);
        if (threshold > 0) {
            role.salienceThreshold = threshold;
        }
        try {
            role.setState(Types.AgentState.from(Json.str(rdata, "state", role.state.value)));
        } catch (IllegalArgumentException ignored) {
        }
        return role;
    }

    /** Rebuild the computer object and bind it to the role (container already exists, ensureContainer is idempotent). */
    @SuppressWarnings("unchecked")
    private void restoreComputers(AgentSystem system, Map<String, Object> computers) {
        Map<String, AgentRole> roles = new LinkedHashMap<>();
        for (AgentRole r : system.pool.allRoles()) {
            roles.put(r.roleId, r);
        }
        List<Map.Entry<String, Object>> todo = new ArrayList<>();
        for (Map.Entry<String, Object> e : computers.entrySet()) {
            AgentRole role = roles.get(e.getKey());
            if (role != null && role.computerIfCreated() == null
                    && e.getValue() instanceof Map) {
                todo.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        // Parallel rebuild: one virtual thread per computer (Java 21+), semaphore throttling to avoid saturating podman
        int maxWorkers = Math.min(10, todo.size());
        java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(maxWorkers);
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        for (Map.Entry<String, Object> item : todo) {
            ex.submit(() -> {
                String rid = item.getKey();
                try {
                    gate.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    Map<String, Object> cdata = (Map<String, Object>) item.getValue();
                    AgentRole role = roles.get(rid);
                    String kind = Json.str(cdata, "kind", "podman");
                    if (!List.of("podman", "local", "ssh").contains(kind)) {
                        kind = "podman";
                    }
                    Computer comp = system.computerManager.create(kind, rid,
                            Json.str(cdata, "name", role.name),
                            Json.boolVal(cdata, "auto_mcp", true),
                            new LinkedHashMap<>());
                    role.bindComputer(comp);
                    comp.powerOn();  // container already exists → idempotent start + MCP reconnect
                    logger.info("StateStore: computer restored and bound → {}", rid);
                } catch (Exception e) {
                    logger.error("StateStore: computer restore failed → {}", rid, e);
                } finally {
                    gate.release();
                }
            });
        }
        ex.shutdown();
        try {
            ex.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
