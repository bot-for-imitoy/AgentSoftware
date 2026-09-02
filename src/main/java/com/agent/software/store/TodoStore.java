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
 * Todo list storage (TodoStore) — per-role personal todo list (JSON persistence, Python version todo_store.py).
 *
 * One independent list per role: data/todos/&lt;role_id&gt;.json.
 */
public class TodoStore {

    private static final Logger logger = LoggerFactory.getLogger(TodoStore.class);

    /** Valid statuses (consistent with the Hermes todo tool). */
    public static final List<String> TODO_STATUSES = List.of("pending", "in_progress", "completed");

    public final String roleId;
    private final Path path;

    public TodoStore(String roleId, String path) {
        this.roleId = roleId != null ? roleId : "";
        this.path = path != null ? Paths.get(path)
                : Paths.get("./data/todos").resolve((this.roleId.isEmpty() ? "shared" : this.roleId) + ".json");
    }

    // ── Low-level read/write ──────────────────────────────────────────

    /** Read the list (returns an empty list if the file does not exist). */
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
            logger.warn("TodoStore[{}] read failed: {}", roleId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Atomic write (tmp + rename). */
    private void save(List<Map<String, Object>> items) {
        try {
            Json.atomicWrite(path, Json.stringifyPretty(items));
        } catch (IOException e) {
            logger.warn("TodoStore[{}] write failed: {}", roleId, e.getMessage());
        }
    }

    // ── CRUD ──────────────────────────────────────────────

    /** Add a todo. Returns the new item Map (containing id/status/created_at). */
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
        logger.info("TodoStore[{}] todo added [{}]: {}", roleId, item.get("id"), title);
        return item;
    }

    /** List todos (sorted by creation time; status filter optional). */
    public List<Map<String, Object>> list(String status) {
        List<Map<String, Object>> items = load();
        if (status != null) {
            items.removeIf(i -> !status.equals(i.get("status")));
        }
        return items;
    }

    /** Update a todo's status. Returns the updated item; returns null if the id does not exist. */
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
                logger.info("TodoStore[{}] todo [{}] → {}", roleId, todoId, status);
                return item;
            }
        }
        return null;
    }

    /** Delete a todo. Returns whether deletion succeeded. */
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
        logger.info("TodoStore[{}] todo deleted [{}]", roleId, todoId);
        return true;
    }
}
