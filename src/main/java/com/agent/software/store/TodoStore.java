package com.agent.software.store;

import com.agent.software.utils.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Todo 清单存储 (TodoStore) — 角色个人待办清单 (JSON 持久化, Python 版 todo_store.py).
 *
 * 每个角色一份独立清单: data/todos/&lt;role_id&gt;.json.
 */
public class TodoStore {

    private static final Logger logger = LoggerFactory.getLogger(TodoStore.class);

    /** 合法状态 (与 Hermes todo 工具一致). */
    public static final List<String> TODO_STATUSES = List.of("pending", "in_progress", "completed");

    public final String roleId;
    private final Path path;

    public TodoStore(String roleId, String path) {
        this.roleId = roleId != null ? roleId : "";
        this.path = path != null ? Paths.get(path)
                : Paths.get("./data/todos").resolve((this.roleId.isEmpty() ? "shared" : this.roleId) + ".json");
    }

    // ── 底层读写 ──────────────────────────────────────────

    /** 读取清单 (文件不存在返回空列表). */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> load() {
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try {
            Object data = Json.parse(Files.readString(path));
            if (data instanceof List) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object o : (List<Object>) data) {
                    if (o instanceof Map) {
                        out.add((Map<String, Object>) o);
                    }
                }
                return out;
            }
            return new ArrayList<>();
        } catch (Exception e) {
            logger.warn("TodoStore[{}] 读取失败: {}", roleId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 原子写 (tmp + rename). */
    private void save(List<Map<String, Object>> items) {
        try {
            Json.atomicWrite(path, Json.stringifyPretty(items));
        } catch (IOException e) {
            logger.warn("TodoStore[{}] 写入失败: {}", roleId, e.getMessage());
        }
    }

    // ── CRUD ──────────────────────────────────────────────

    /** 添加待办. 返回新条目 Map (含 id/status/created_at). */
    public Map<String, Object> add(String title, String detail) {
        double now = System.currentTimeMillis() / 1000.0;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        item.put("title", title);
        item.put("detail", detail == null ? "" : detail);
        item.put("status", "pending");
        item.put("created_at", now);
        item.put("updated_at", now);
        List<Map<String, Object>> items = load();
        items.add(item);
        save(items);
        logger.info("TodoStore[{}] 已添加待办 [{}]: {}", roleId, item.get("id"), title);
        return item;
    }

    /** 列出待办 (按创建时间排序; status 过滤可选). */
    public List<Map<String, Object>> list(String status) {
        List<Map<String, Object>> items = load();
        if (status != null) {
            items.removeIf(i -> !status.equals(i.get("status")));
        }
        return items;
    }

    /** 更新待办状态. 返回更新后的条目; id 不存在返回 null. */
    public Map<String, Object> update(String todoId, String status) {
        if (!TODO_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Invalid status '" + status + "', allowed: " + String.join(", ", TODO_STATUSES));
        }
        List<Map<String, Object>> items = load();
        for (Map<String, Object> item : items) {
            if (todoId.equals(item.get("id"))) {
                item.put("status", status);
                item.put("updated_at", System.currentTimeMillis() / 1000.0);
                save(items);
                logger.info("TodoStore[{}] 待办 [{}] → {}", roleId, todoId, status);
                return item;
            }
        }
        return null;
    }

    /** 删除待办. 返回是否删除成功. */
    public boolean delete(String todoId) {
        List<Map<String, Object>> items = load();
        List<Map<String, Object>> kept = new ArrayList<>();
        boolean found = false;
        for (Map<String, Object> item : items) {
            if (todoId.equals(item.get("id"))) {
                found = true;
            } else {
                kept.add(item);
            }
        }
        if (!found) {
            return false;
        }
        save(kept);
        logger.info("TodoStore[{}] 待办已删除 [{}]", roleId, todoId);
        return true;
    }
}
