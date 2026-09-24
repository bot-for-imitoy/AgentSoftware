package com.agent.software.store;

import com.agent.software.utils.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务看板：一个角色一个 JSON 文件，按**组**分桶存放它派出去的排期任务，每次改动立刻落盘。
 *
 * <pre>
 *   &lt;base&gt;/&lt;role_id&gt;.json
 *   {
 *     "current_group": "default",
 *     "groups": { "default": [ {"id","title","detail","status","target","due","tokens",
 *                               "created_at","updated_at"} ], ... }
 *   }
 * </pre>
 *
 * <p>组是"套在任务外面的一层"：每个组有自己的任务，可以单独创建；{@code current_group} 是
 * **基线组**，不带 {@code group} 参数的任务操作都作用在它上面，随时可以 {@link #switchGroup(String)}
 * 换一个组当基线。
 *
 * <p>记录里的 {@code id} 就是那条 {@code event.Task} 的 uuid，所以任务跑完后
 * {@link #recordStatus(String, String, int)} 能把完成情况写回同一条记录（"完成情况实时保存"）。
 *
 * <p>线程契约：方法都是 synchronized —— 派活的人写自己的看板，被派的人跑完任务时会回调更新，
 * 所以这里确实可能被两个 worker 线程碰。
 */
public class TaskBoard {

    private static final Logger logger = LoggerFactory.getLogger(TaskBoard.class);

    /** 默认组名。 */
    public static final String DEFAULT_GROUP = "default";

    /** 看板上的一条任务记录。 */
    public static final class Record {
        public String id = "";
        public String title = "";
        public String detail = "";
        public String status = "pending";
        public String target = "";
        public String due = "";
        public int tokens;
        public long createdAt;
        public long updatedAt;
    }

    private final Path file;
    private final String roleId;
    private final Map<String, List<Record>> groups = new LinkedHashMap<>();
    private String currentGroup = DEFAULT_GROUP;

    public TaskBoard(Path baseDir, String roleId) {
        Path base = baseDir == null ? Paths.get("data", "task_groups") : baseDir;
        this.roleId = roleId == null ? "" : roleId;
        this.file = base.resolve(this.roleId + ".json");
        load();
    }

    public TaskBoard(String baseDir, String roleId) {
        this(baseDir == null || baseDir.isBlank() ? null : Paths.get(baseDir), roleId);
    }

    public Path file() {
        return file;
    }

    // ── 组 ──────────────────────────────────────────────────────

    public synchronized List<String> groups() {
        List<String> names = new ArrayList<>(groups.keySet());
        names.sort((a, b) -> {
            if (a.equals(DEFAULT_GROUP)) {
                return -1;
            }
            if (b.equals(DEFAULT_GROUP)) {
                return 1;
            }
            return a.compareTo(b);
        });
        return names;
    }

    public synchronized String currentGroup() {
        return currentGroup;
    }

    /** 解析用户给的组名（空 = 当前基线组）。 */
    public synchronized String resolveGroup(String name) {
        return normalizeGroup(name);
    }

    /**
     * 切换基线组；组不存在就新建。
     *
     * @return true = 新建了这个组
     */
    public synchronized boolean switchGroup(String name) {
        String key = normalizeGroup(name);
        boolean created = groups.putIfAbsent(key, new ArrayList<>()) == null;
        currentGroup = key;
        save();
        logger.info("TaskBoard[{}] baseline group = {}{}", roleId, key, created ? " (created)" : "");
        return created;
    }

    public synchronized int count(String group) {
        List<Record> list = groups.get(normalizeGroup(group));
        return list == null ? 0 : list.size();
    }

    public synchronized List<Record> items(String group) {
        List<Record> list = groups.get(normalizeGroup(group));
        return list == null ? List.of() : new ArrayList<>(list);
    }

    // ── 任务记录 ────────────────────────────────────────────────

    /** 在某个组里登记一条排期任务（group 为空 = 当前基线组）。 */
    public synchronized Record add(String id, String title, String detail, String target,
                                   String due, String group) {
        String key = normalizeGroup(group);
        List<Record> list = groups.computeIfAbsent(key, k -> new ArrayList<>());
        Record r = new Record();
        r.id = id == null ? "" : id;
        r.title = title == null ? "" : title.strip();
        r.detail = detail == null ? "" : detail.strip();
        r.target = target == null ? "" : target;
        r.due = due == null ? "" : due;
        r.status = "pending";
        r.createdAt = System.currentTimeMillis();
        r.updatedAt = r.createdAt;
        list.add(r);
        save();
        logger.info("TaskBoard[{}] task {} registered in group {}", roleId, r.id, key);
        return r;
    }

    /** 找一条记录（在指定/基线组里，按 id 或唯一前缀）。 */
    public synchronized Record find(String group, String idOrPrefix) {
        List<Record> list = groups.get(normalizeGroup(group));
        return findIn(list, idOrPrefix);
    }

    /** 在所有组里找（用于"任务跑完回写状态"——不关心它当时被放在哪个组）。 */
    public synchronized Record findAnywhere(String id) {
        for (List<Record> list : groups.values()) {
            Record r = findIn(list, id);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /**
     * 任务跑完后回写状态（实时保存）。记录不在看板上就什么都不做
     * （系统派的任务、从没被登记过的任务都不该凭空建文件）。
     */
    public synchronized boolean recordStatus(String id, String status, int tokens) {
        if (id == null || id.isBlank()) {
            return false;
        }
        for (List<Record> list : groups.values()) {
            for (Record r : list) {
                if (r.id.equals(id)) {
                    r.status = status == null ? r.status : status;
                    r.tokens = tokens;
                    r.updatedAt = System.currentTimeMillis();
                    save();
                    return true;
                }
            }
        }
        return false;
    }

    /** 改记录的文字/目标/时间；改到返回 true。 */
    public synchronized boolean update(String id, String title, String detail, String target, String due) {
        Record r = findAnywhere(id);
        if (r == null) {
            return false;
        }
        if (title != null && !title.isBlank()) {
            r.title = title.strip();
        }
        if (detail != null && !detail.isBlank()) {
            r.detail = detail.strip();
        }
        if (target != null && !target.isBlank()) {
            r.target = target;
        }
        if (due != null && !due.isBlank()) {
            r.due = due;
        }
        r.updatedAt = System.currentTimeMillis();
        save();
        return true;
    }

    /** 从看板上删掉一条记录（id 或唯一前缀）。 */
    public synchronized boolean remove(String idOrPrefix) {
        for (List<Record> list : groups.values()) {
            Record r = findIn(list, idOrPrefix);
            if (r != null) {
                list.remove(r);
                save();
                return true;
            }
        }
        return false;
    }

    // ── 持久化 ──────────────────────────────────────────────────

    private void load() {
        groups.clear();
        currentGroup = DEFAULT_GROUP;
        groups.put(DEFAULT_GROUP, new ArrayList<>());
        if (!Files.exists(file)) {
            return;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("TaskBoard[{}] cannot read {}: {}", roleId, file, e.toString());
            return;
        }
        if (text.isBlank()) {
            return;
        }
        try {
            Map<String, Object> root = Json.parseObject(text);
            Object current = root.get("current_group");
            if (current != null && !String.valueOf(current).isBlank()) {
                currentGroup = String.valueOf(current).strip();
            }
            Object g = root.get("groups");
            if (g instanceof Map<?, ?> map) {
                groups.clear();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    List<Object> raw = e.getValue() instanceof List<?> l
                            ? new ArrayList<>(l) : new ArrayList<>();
                    groups.put(String.valueOf(e.getKey()), parseRecords(raw));
                }
            }
            if (groups.isEmpty()) {
                groups.put(DEFAULT_GROUP, new ArrayList<>());
            }
            if (!groups.containsKey(currentGroup)) {
                currentGroup = DEFAULT_GROUP;
            }
        } catch (Exception e) {
            logger.warn("TaskBoard[{}] cannot parse {}: {}", roleId, file, e.toString());
        }
    }

    private void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("current_group", currentGroup);
        Map<String, Object> g = new LinkedHashMap<>();
        for (Map.Entry<String, List<Record>> e : groups.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Record r : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.id);
                m.put("title", r.title);
                m.put("detail", r.detail);
                m.put("status", r.status);
                m.put("target", r.target);
                m.put("due", r.due);
                m.put("tokens", r.tokens);
                m.put("created_at", r.createdAt);
                m.put("updated_at", r.updatedAt);
                list.add(m);
            }
            g.put(e.getKey(), list);
        }
        root.put("groups", g);
        Json.writeFile(file, root);
    }

    // ── 小工具 ──────────────────────────────────────────────────

    private String normalizeGroup(String name) {
        if (name == null || name.isBlank()) {
            return currentGroup;
        }
        String cleaned = name.strip().replaceAll("[\\s/\\\\:*?\"<>|]+", "_");
        return cleaned.isEmpty() ? currentGroup : cleaned;
    }

    private static Record findIn(List<Record> list, String idOrPrefix) {
        if (list == null || idOrPrefix == null || idOrPrefix.isBlank()) {
            return null;
        }
        String needle = idOrPrefix.strip();
        for (Record r : list) {
            if (r.id.equals(needle)) {
                return r;
            }
        }
        Record hit = null;
        for (Record r : list) {
            if (r.id.startsWith(needle)) {
                if (hit != null) {
                    return null;   // 前缀有歧义
                }
                hit = r;
            }
        }
        return hit;
    }

    private static List<Record> parseRecords(List<?> raw) {
        List<Record> out = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof Map<?, ?> m) {
                Record r = new Record();
                r.id = str(m.get("id"));
                r.title = str(m.get("title"));
                r.detail = str(m.get("detail"));
                r.status = str(m.get("status")).isEmpty() ? "pending" : str(m.get("status"));
                r.target = str(m.get("target"));
                r.due = str(m.get("due"));
                r.tokens = (int) num(m.get("tokens"));
                r.createdAt = num(m.get("created_at"));
                r.updatedAt = num(m.get("updated_at"));
                out.add(r);
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(str(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
