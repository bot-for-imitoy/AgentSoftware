package com.agent.software.adapters.persistence;

import com.agent.software.ports.TodoRepository;
import com.agent.software.store.NoteStore;
import com.agent.software.store.TodoStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link TodoRepository} backed by the existing JSON {@code TodoStore}. */
public final class JsonTodoRepository implements TodoRepository {

    private final Path baseDir;
    private final Map<String, TodoStore> stores = new ConcurrentHashMap<>();

    public JsonTodoRepository(Path baseDir) {
        this.baseDir = baseDir == null ? Path.of("data", "todos") : baseDir;
    }

    private TodoStore store(String roleId) {
        String key = roleId == null || roleId.isBlank() ? "shared" : roleId;
        return stores.computeIfAbsent(key, r -> new TodoStore(
                r, baseDir.resolve(NoteStore.sanitizeTitle(r) + ".json").toString()));
    }

    @Override
    public List<TodoItem> list(String roleId, String status) {
        List<Map<String, Object>> raw = store(roleId).list(status);
        List<TodoItem> out = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            out.add(convert(m));
        }
        return out;
    }

    @Override
    public TodoItem add(String roleId, String title, String detail) {
        return convert(store(roleId).add(title, detail));
    }

    @Override
    public Optional<TodoItem> update(String roleId, String id, String status) {
        Map<String, Object> updated = store(roleId).update(id, status);
        return updated == null ? Optional.empty() : Optional.of(convert(updated));
    }

    @Override
    public boolean delete(String roleId, String id) {
        return store(roleId).delete(id);
    }

    private static TodoItem convert(Map<String, Object> m) {
        return new TodoItem(
                string(m.get("id")),
                string(m.get("title")),
                string(m.get("detail")),
                string(m.get("status")),
                number(m.get("created_at")),
                number(m.get("updated_at")));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }
}
