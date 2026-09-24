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
import java.util.Locale;
import java.util.Map;

/**
 * 待办存储：一个角色一个 JSON 文件，按**组**分桶，每次改动立刻落盘。
 *
 * <pre>
 *   &lt;base&gt;/&lt;role_id&gt;.json
 *   {
 *     "current_group": "default",
 *     "groups": { "default": [ {"id","title","detail","status","created_at","updated_at"} ], ... }
 *   }
 * </pre>
 *
 * <p>组是"套在待办外面的一层"：每个组有自己的事项，可以单独增删改；
 * {@code current_group} 是"基线组"，不带 {@code group} 参数的操作都作用在它上面，
 * 员工随时可以 {@link #switchGroup(String)} 换一个组当基线。
 *
 * <p>兼容 master 时代的旧格式（文件直接是一个事项数组）：读到时自动装进 {@code default} 组。
 *
 * <p>线程契约：方法都是 synchronized（角色 worker 单线程调用，加锁只是防御）。
 */
public class TodoStore {

    private static final Logger logger = LoggerFactory.getLogger(TodoStore.class);

    /** 默认组名，也是没有任何组时的落点。 */
    public static final String DEFAULT_GROUP = "default";
    /** 允许的事项状态（与 master/Python 版一致）。 */
    public static final List<String> STATUSES = List.of("pending", "in_progress", "completed");

    /** 一条待办。字段可变（JSON 回填 + 状态更新）。 */
    public static final class Item {
        public String id = "";
        public String title = "";
        public String detail = "";
        public String status = "pending";
        public long createdAt;
        public long updatedAt;
    }

    private final Path file;
    private final String roleId;
    private final Map<String, List<Item>> groups = new LinkedHashMap<>();
    private String currentGroup = DEFAULT_GROUP;

    public TodoStore(Path baseDir, String roleId) {
        Path base = baseDir == null ? Paths.get("data", "todos") : baseDir;
        this.roleId = roleId == null ? "" : roleId;
        this.file = base.resolve(this.roleId + ".json");
        load();
    }

    public TodoStore(String baseDir, String roleId) {
        this(baseDir == null || baseDir.isBlank() ? null : Paths.get(baseDir), roleId);
    }

    public Path file() {
        return file;
    }

    public String roleId() {
        return roleId;
    }

    // ── 组 ──────────────────────────────────────────────────────

    /** 所有组名（default 排最前，其余按创建/字典序），保证至少有一个组。 */
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

    public synchronized boolean hasGroup(String name) {
        return name != null && groups.containsKey(name);
    }

    public synchronized String currentGroup() {
        return currentGroup;
    }

    /**
     * 切换基线组；组不存在就新建（"随时切换另一个 todo 组作为基线"）。
     *
     * @return true = 新建了这个组，false = 切到已有组
     */
    public synchronized boolean switchGroup(String name) {
        String key = normalizeGroup(name);
        boolean created = groups.putIfAbsent(key, new ArrayList<>()) == null;
        currentGroup = key;
        save();
        logger.info("TodoStore[{}] baseline group = {}{}", roleId, key, created ? " (created)" : "");
        return created;
    }

    /** 组内事项数。 */
    public synchronized int count(String group) {
        List<Item> items = groups.get(normalizeGroup(group));
        return items == null ? 0 : items.size();
    }

    /** 解析用户给的组名（空 = 当前基线组），返回真正会被使用的组名。 */
    public synchronized String resolveGroup(String name) {
        return normalizeGroup(name);
    }

    // ── 事项 ────────────────────────────────────────────────────

    /** 某个组的事项（group 为空 = 当前基线组）；组不存在返回空列表。 */
    public synchronized List<Item> items(String group) {
        List<Item> items = groups.get(normalizeGroup(group));
        return items == null ? List.of() : new ArrayList<>(items);
    }

    /** 加一条事项（group 为空 = 当前基线组；组不存在则新建）。 */
    public synchronized Item add(String title, String detail, String group) {
        String key = normalizeGroup(group);
        List<Item> items = groups.computeIfAbsent(key, k -> new ArrayList<>());
        Item item = new Item();
        item.id = newId();
        item.title = title == null ? "" : title.strip();
        item.detail = detail == null ? "" : detail.strip();
        item.status = "pending";
        item.createdAt = System.currentTimeMillis();
        item.updatedAt = item.createdAt;
        items.add(item);
        save();
        logger.info("TodoStore[{}] todo added to group {}: {}", roleId, key, item.id);
        return item;
    }

    /**
     * 改一条事项（按 id 或 id 前缀在**当前基线组**里找）。
     *
     * @param status null/空 = 不改状态
     * @return 改到的条目；找不到返回 null
     */
    public synchronized Item update(String idOrPrefix, String status, String title, String detail) {
        Item item = find(currentGroup, idOrPrefix);
        if (item == null) {
            return null;
        }
        if (status != null && !status.isBlank()) {
            item.status = normalizeStatus(status);
        }
        if (title != null && !title.isBlank()) {
            item.title = title.strip();
        }
        if (detail != null && !detail.isBlank()) {
            item.detail = detail.strip();
        }
        item.updatedAt = System.currentTimeMillis();
        save();
        return item;
    }

    /** 删一条事项（按 id 或 id 前缀在当前基线组里找）。 */
    public synchronized boolean delete(String idOrPrefix) {
        Item item = find(currentGroup, idOrPrefix);
        if (item == null) {
            return false;
        }
        groups.get(currentGroup).remove(item);
        save();
        return true;
    }

    /** 在当前基线组里按 id / 前缀找一条；找不到返回 null。 */
    public synchronized Item find(String group, String idOrPrefix) {
        String key = normalizeGroup(group);
        List<Item> items = groups.get(key);
        if (items == null || idOrPrefix == null || idOrPrefix.isBlank()) {
            return null;
        }
        String needle = idOrPrefix.strip();
        for (Item it : items) {
            if (it.id.equals(needle)) {
                return it;
            }
        }
        Item hit = null;
        for (Item it : items) {
            if (it.id.startsWith(needle)) {
                if (hit != null) {
                    return null;   // 前缀有歧义
                }
                hit = it;
            }
        }
        return hit;
    }

    // ── 持久化 ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
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
            logger.warn("TodoStore[{}] cannot read {}: {}", roleId, file, e.toString());
            return;
        }
        if (text.isBlank()) {
            return;
        }
        try {
            String trimmed = text.stripLeading();
            if (trimmed.startsWith("[")) {
                // master 时代的旧格式：文件就是一个事项数组 → 装进 default 组
                groups.put(DEFAULT_GROUP, parseItems(Json.parseArray(text)));
                logger.info("TodoStore[{}] migrated {} legacy todo item(s) into group '{}'",
                        roleId, groups.get(DEFAULT_GROUP).size(), DEFAULT_GROUP);
                return;
            }
            Map<String, Object> root = Json.parseObject(text);
            Object current = root.get("current_group");
            if (current != null && !String.valueOf(current).isBlank()) {
                currentGroup = String.valueOf(current).strip();
            }
            Object g = root.get("groups");
            if (g instanceof Map<?, ?> map) {
                groups.clear();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String name = String.valueOf(e.getKey());
                    List<Object> raw = e.getValue() instanceof List<?> l
                            ? new ArrayList<>(l) : new ArrayList<>();
                    groups.put(name, parseItems(raw));
                }
            }
            if (groups.isEmpty()) {
                groups.put(DEFAULT_GROUP, new ArrayList<>());
            }
            if (!groups.containsKey(currentGroup)) {
                currentGroup = DEFAULT_GROUP;
            }
        } catch (Exception e) {
            logger.warn("TodoStore[{}] cannot parse {}: {}", roleId, file, e.toString());
        }
    }

    /** 落盘（每次改动都调，保证"组内事项的完成情况实时保存"）。 */
    private void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("current_group", currentGroup);
        Map<String, Object> g = new LinkedHashMap<>();
        for (Map.Entry<String, List<Item>> e : groups.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Item it : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", it.id);
                m.put("title", it.title);
                m.put("detail", it.detail);
                m.put("status", it.status);
                m.put("created_at", it.createdAt);
                m.put("updated_at", it.updatedAt);
                list.add(m);
            }
            g.put(e.getKey(), list);
        }
        root.put("groups", g);
        Json.writeFile(file, root);
    }

    // ── 小工具 ──────────────────────────────────────────────────

    /** 组名规范化：空 → 当前基线组；非法字符换成下划线。 */
    private String normalizeGroup(String name) {
        if (name == null || name.isBlank()) {
            return currentGroup;
        }
        String cleaned = name.strip().replaceAll("[\\s/\\\\:*?\"<>|]+", "_");
        return cleaned.isEmpty() ? currentGroup : cleaned;
    }

    private static String normalizeStatus(String status) {
        String s = status.strip().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (s.equals("done") || s.equals("complete")) {
            return "completed";
        }
        if (s.equals("doing") || s.equals("wip") || s.equals("inprogress")) {
            return "in_progress";
        }
        return STATUSES.contains(s) ? s : "pending";
    }

    private static List<Item> parseItems(List<?> raw) {
        List<Item> out = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof Map<?, ?> m) {
                out.add(itemFrom(m));
            }
        }
        return out;
    }

    private static Item itemFrom(Map<?, ?> m) {
        Item it = new Item();
        it.id = str(m.get("id"));
        it.title = str(m.get("title"));
        it.detail = str(m.get("detail"));
        it.status = normalizeStatus(str(m.get("status")).isEmpty() ? "pending" : str(m.get("status")));
        it.createdAt = num(m.get("created_at"));
        it.updatedAt = num(m.get("updated_at"));
        return it;
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

    private static String newId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
