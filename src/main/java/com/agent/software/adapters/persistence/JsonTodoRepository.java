package com.agent.software.adapters.persistence;

import com.agent.software.kernel.AgentException;
import com.agent.software.kernel.Names;
import com.agent.software.ports.TodoRepository;
import com.agent.software.utils.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** File-backed {@link TodoRepository}: one JSON array per role. */
public final class JsonTodoRepository implements TodoRepository {

    private final Path base;

    public JsonTodoRepository(Path base) {
        this.base = base == null ? Path.of("data", "todos") : base;
    }

    private Path file(String roleId) {
        return base.resolve(Names.sanitize(roleId == null || roleId.isBlank() ? "shared" : roleId) + ".json");
    }

    @Override
    public List<TodoItem> list(String roleId, String status) {
        List<TodoItem> out = new ArrayList<>();
        for (Map<String, Object> item : load(roleId)) {
            TodoItem converted = convert(item);
            if (status == null || status.equals(converted.status())) {
                out.add(converted);
            }
        }
        return out;
    }

    @Override
    public TodoItem add(String roleId, String title, String detail) {
        List<Map<String, Object>> items = load(roleId);
        double now = System.currentTimeMillis() / 1000.0;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        item.put("title", title == null ? "" : title);
        item.put("detail", detail == null ? "" : detail);
        item.put("status", "pending");
        item.put("created_at", now);
        item.put("updated_at", now);
        items.add(item);
        save(roleId, items);
        return convert(item);
    }

    @Override
    public Optional<TodoItem> update(String roleId, String id, String status) {
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("Invalid status '" + status + "', allowed: " + STATUSES);
        }
        List<Map<String, Object>> items = load(roleId);
        for (Map<String, Object> item : items) {
            if (id != null && id.equals(String.valueOf(item.get("id")))) {
                item.put("status", status);
                item.put("updated_at", System.currentTimeMillis() / 1000.0);
                save(roleId, items);
                return Optional.of(convert(item));
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean delete(String roleId, String id) {
        List<Map<String, Object>> items = load(roleId);
        boolean removed = items.removeIf(item -> id != null && id.equals(String.valueOf(item.get("id"))));
        if (removed) {
            save(roleId, items);
        }
        return removed;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> load(String roleId) {
        Path path = file(roleId);
        if (!Files.isRegularFile(path)) {
            return new ArrayList<>();
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return new ArrayList<>();
            }
            Object parsed = Json.parse(text);
            if (!(parsed instanceof List<?> list)) {
                return new ArrayList<>();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    private void save(String roleId, List<Map<String, Object>> items) {
        try {
            Json.atomicWrite(file(roleId), Json.stringifyPretty(items));
        } catch (IOException e) {
            throw new AgentException.PortException("cannot save todos: " + file(roleId), e);
        }
    }

    private static TodoItem convert(Map<String, Object> item) {
        return new TodoItem(
                string(item.get("id")),
                string(item.get("title")),
                string(item.get("detail")),
                string(item.get("status")),
                number(item.get("created_at")),
                number(item.get("updated_at")));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }
}
