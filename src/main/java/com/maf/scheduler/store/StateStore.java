package com.maf.scheduler.store;

import com.maf.scheduler.computers.Computer;
import com.maf.scheduler.core.*;
import com.maf.scheduler.computers.ComputerManager;
import com.maf.scheduler.event.TimeEventBus;
import com.maf.scheduler.role.AgentRole;
import com.maf.scheduler.role.RolePool;
import com.maf.scheduler.utils.Json;
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
 * 统一状态存储 (StateStore) — 结构化数据 + 对话内容 + 容器信息持久化
 * (Python 版 state_store.py).
 *
 * 把所有可序列化状态汇总到单个 JSON 文件 (默认 data/state.json):
 * 角色档案 / 任务历史 / 未完成任务 / 电脑容器信息 / 时间进度.
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

    /** 保存系统全部状态到 JSON (原子写). 返回存档文件路径. */
    public String save(AgentSystem system) {
        Map<String, Object> data = collect(system);
        try {
            Json.atomicWrite(path, Json.stringifyPretty(data));
        } catch (IOException e) {
            throw new RuntimeException("保存状态失败: " + path, e);
        }
        int historyCount = 0;
        for (Map<String, Object> r : (List<Map<String, Object>>) data.get("roles")) {
            historyCount += ((List<?>) r.get("history")).size();
        }
        logger.info("StateStore: 状态已保存 → {} (角色 {}, 任务历史 {})",
                path, ((List<?>) data.get("roles")).size(), historyCount);
        return path.toString();
    }

    /** 从存档恢复系统状态 (角色档案/任务/容器/时间). 返回恢复的角色数. */
    public int restore(AgentSystem system) {
        if (!exists()) {
            return 0;
        }
        Map<String, Object> data;
        try {
            data = Json.parseObject(Files.readString(path));
        } catch (Exception e) {
            logger.error("StateStore: 存档读取失败, 跳过恢复: {}", e.getMessage());
            return 0;
        }
        Object version = data.get("version");
        if (!Integer.valueOf(VERSION).equals(version instanceof Number n ? n.intValue() : version)) {
            logger.warn("StateStore: 存档版本 {} ≠ 当前 {}, 跳过恢复", version, VERSION);
            return 0;
        }
        return apply(data, system);
    }

    // ── 收集 (内存 → Map) ───────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> collect(AgentSystem system) {
        RolePool pool = system.pool;
        TimeEventBus tm = system.timeManager;

        List<Map<String, Object>> rolesData = new ArrayList<>();
        for (AgentRole role : pool.allRoles()) {
            rolesData.add(roleToDict(role));
        }

        // 电脑/容器信息
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
            c.put("name", ComputerManager.getInstance().nameOf(role.roleId, role.name));
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

    /** 角色档案 + 任务 (队列待办 + 历史) → Map. */
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

    // ── 应用 (Map → 内存) ───────────────────────────────

    @SuppressWarnings("unchecked")
    private int apply(Map<String, Object> data, AgentSystem system) {
        RolePool pool = system.pool;
        int restored = 0;

        // 1) 角色档案 + 任务
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

        // 2) 电脑/容器: 重建对象并绑定已存在的容器 (不重建容器)
        Object computersObj = data.get("computers");
        if (computersObj instanceof Map) {
            restoreComputers(system, (Map<String, Object>) computersObj);
        }

        // 3) 时间进度 (start() 时应用)
        Map<String, Object> t = (Map<String, Object>) data.getOrDefault("time", new LinkedHashMap<>());
        int day = t.get("day") instanceof Number n ? n.intValue() : 1;
        int tod = t.get("tick_of_day") instanceof Number n2 ? n2.intValue() : 0;
        system.timeManager.setProgress(day, tod);

        logger.info("StateStore: 已恢复 {} 个角色 → {}", restored, system.timeManager.describe());
        return restored;
    }

    /** 按存档恢复单个角色: 已注册则覆盖字段, 未注册则重建并装配. */
    @SuppressWarnings("unchecked")
    private AgentRole restoreRole(RolePool pool, Map<String, Object> rdata, AgentSystem system) {
        String roleId = Json.str(rdata, "role_id", "");
        AgentRole role = pool.getRoleOrNull(roleId);
        if (role == null) {
            // 存档里的角色不在当前模板池: 重建并装配
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
        // 覆盖档案字段 (模板创建的角色以存档为准)
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

    /** 重建电脑对象并绑定到角色 (容器已存在, ensureContainer 幂等). */
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
        // 并行重建: 每台电脑一个虚拟线程 (Java 21+), 信号量限流避免 podman 打满
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
                    Computer comp = ComputerManager.getInstance().create(kind, rid,
                            Json.str(cdata, "name", role.name),
                            Json.boolVal(cdata, "auto_mcp", true),
                            new LinkedHashMap<>());
                    role.bindComputer(comp);
                    comp.powerOn();  // 容器已存在 → 幂等启动 + MCP 重连
                    logger.info("StateStore: 电脑已恢复绑定 → {}", rid);
                } catch (Exception e) {
                    logger.error("StateStore: 电脑恢复失败 → {}", rid, e);
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
