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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 待办存储：一个角色一个 JSON 文件，**一层平铺的事项列表**（没有"组"的概念 —— 那是 task 系列的东西）。
 *
 * <pre>
 *   &lt;base&gt;/&lt;role_id&gt;.json
 *   [ {"id","title","detail","status","created_at","updated_at"} ]
 * </pre>
 *
 * <p>每次改动立刻落盘（add/update/delete 都保存），所以完成情况不会因为进程退出而丢。
 *
 * <p>兼容两种旧格式：master 时代的裸数组（本来就是本格式），以及本仓库中途出现过的
 * {@code {"groups": {...}}} 结构（读到时把各组事项拍平合并）。
 *
 * <p>线程契约：方法都是 synchronized（角色 worker 单线程调用，加锁只是防御）。
 */
public class TodoStore {

    private static final Logger logger = LoggerFactory.getLogger(TodoStore.class);

    /** 允许的事项状态（与 master/Python 版一致）。 */
    public static final List<String> STATUSES = List.of("pending", "in_progress", "completed");

    /** 一条待办。 */
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
    private final List<Item> items = new ArrayList<>();

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

    // ── 事项 ────────────────────────────────────────────────────

    /** 全部事项（按加入顺序）。 */
    public synchronized List<Item> items() {
        return new ArrayList<>(items);
    }

    public synchronized int count() {
        return items.size();
    }

    /** 加一条事项。 */
    public synchronized Item add(String title, String detail) {
        Item item = new Item();
        item.id = newId();
        item.title = title == null ? "" : title.strip();
        item.detail = detail == null ? "" : detail.strip();
        item.status = "pending";
        item.createdAt = System.currentTimeMillis();
        item.updatedAt = item.createdAt;
        items.add(item);
        save();
        logger.info("TodoStore[{}] todo added: {}", roleId, item.id);
        return item;
    }

    /**
     * 改一条事项（按 id 或唯一前缀）。
     *
     * @param status null/空 = 不改状态
     * @return 改到的条目；找不到（或前缀有歧义）返回 null
     */
    public synchronized Item update(String idOrPrefix, String status, String title, String detail) {
        Item item = find(idOrPrefix);
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

    /** 删一条事项。 */
    public synchronized boolean delete(String idOrPrefix) {
        Item item = find(idOrPrefix);
        if (item == null) {
            return false;
        }
        items.remove(item);
        save();
        return true;
    }

    /** 按 id / 唯一前缀找一条；找不到或前缀有歧义返回 null。 */
    public synchronized Item find(String idOrPrefix) {
        if (idOrPrefix == null || idOrPrefix.isBlank()) {
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

    private void load() {
        items.clear();
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
                items.addAll(parseItems(Json.parseArray(text)));
                return;
            }
            // 中途出现过的 {"groups": {...}} 结构：拍平合并
            Map<String, Object> root = Json.parseObject(text);
            Object groups = root.get("groups");
            if (groups instanceof Map<?, ?> map) {
                for (Object raw : map.values()) {
                    if (raw instanceof List<?> list) {
                        items.addAll(parseItems(list));
                    }
                }
                logger.info("TodoStore[{}] flattened {} item(s) from a legacy grouped file",
                        roleId, items.size());
            }
        } catch (Exception e) {
            logger.warn("TodoStore[{}] cannot parse {}: {}", roleId, file, e.toString());
        }
    }

    /** 落盘（每次改动都调，保证完成情况实时保存）。 */
    private void save() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item it : items) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", it.id);
            m.put("title", it.title);
            m.put("detail", it.detail);
            m.put("status", it.status);
            m.put("created_at", it.createdAt);
            m.put("updated_at", it.updatedAt);
            out.add(m);
        }
        Json.writeFile(file, out);
    }

    // ── 小工具 ──────────────────────────────────────────────────

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
