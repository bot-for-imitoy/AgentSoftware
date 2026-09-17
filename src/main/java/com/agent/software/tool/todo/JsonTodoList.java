package com.agent.software.tool.todo;

import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JsonCodec;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TodoId;
import com.agent.software.kernel.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.agent.software.tool.todo.Todo.TodoStatus;

/**
 * JSON 文件形态的待办清单：按角色存放，状态迁移显式落盘。
 *
 * <p>落盘形状 {@code {"todos":[{id,title,detail,status}]}}；{@link Todo} 本身没有时间字段，
 * 因此列表顺序即创建顺序，{@code add} 追加到末尾。
 *
 * <p>写入走 {@code .tmp + move} 原子替换；按 owner 分锁，同一角色的读改写串行。
 */
public final class JsonTodoList implements TodoList {

    private static final Logger log = LoggerFactory.getLogger(JsonTodoList.class);

    private final AppPaths paths;
    private final JsonCodec json;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 绑定数据路径与 JSON 编解码器。 */
    public JsonTodoList(AppPaths paths, JsonCodec json) {
        this.paths = paths;
        this.json = json;
    }

    @Override
    public List<Todo> list(RoleId owner, TodoStatus filter) {
        requireOwner(owner);
        ReentrantLock lock = lock(owner);
        lock.lock();
        try {
            List<Todo> items = load(owner);
            if (filter == null) {
                return new ArrayList<>(items);
            }
            List<Todo> out = new ArrayList<>();
            for (Todo item : items) {
                if (item.status() == filter) {
                    out.add(item);
                }
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Todo add(RoleId owner, String title, String detail) {
        requireOwner(owner);
        if (Text.isBlank(title)) {
            throw new DomainError("todo.title.blank", "待办标题不能为空");
        }
        ReentrantLock lock = lock(owner);
        lock.lock();
        try {
            List<Todo> items = load(owner);
            Todo todo = new Todo(newId(), owner, title.strip(),
                    detail == null ? "" : detail.strip(), TodoStatus.PENDING);
            items.add(todo);
            save(owner, items);
            return todo;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Todo> update(RoleId owner, TodoId id, TodoStatus status) {
        requireOwner(owner);
        if (id == null || status == null) {
            return Optional.empty();
        }
        ReentrantLock lock = lock(owner);
        lock.lock();
        try {
            List<Todo> items = load(owner);
            for (int i = 0; i < items.size(); i++) {
                if (id.value().equals(items.get(i).id().value())) {
                    Todo updated = new Todo(items.get(i).id(), owner, items.get(i).title(),
                            items.get(i).detail(), status);
                    items.set(i, updated);
                    save(owner, items);
                    return Optional.of(updated);
                }
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean delete(RoleId owner, TodoId id) {
        requireOwner(owner);
        if (id == null) {
            return false;
        }
        ReentrantLock lock = lock(owner);
        lock.lock();
        try {
            List<Todo> items = load(owner);
            boolean removed = items.removeIf(t -> id.value().equals(t.id().value()));
            if (removed) {
                save(owner, items);
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    // ── 内部：文件读写 ──────────────────────────────────────────────

    private Path file(RoleId owner) {
        return paths.dataFile("todos", owner.value() + ".json");
    }

    /** 读取一个角色的待办；文件不存在或损坏时返回空表（损坏只 warn，不抛）。 */
    private List<Todo> load(RoleId owner) {
        Path file = file(owner);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            Map<String, Object> root = json.readMap(Files.readString(file, StandardCharsets.UTF_8));
            List<Todo> out = new ArrayList<>();
            if (root.get("todos") instanceof List<?> raw) {
                for (Object item : raw) {
                    if (item instanceof Map<?, ?> map) {
                        Todo todo = fromMap(owner, map);
                        if (todo != null) {
                            out.add(todo);
                        }
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("待办文件损坏，按空表处理: {} ({})", file, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 原子写：先写 .tmp 再 move。 */
    private void save(RoleId owner, List<Todo> items) {
        Path file = paths.ensure(file(owner));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        List<Map<String, Object>> raw = new ArrayList<>();
        for (Todo item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.id().value());
            m.put("title", item.title());
            m.put("detail", Text.orEmpty(item.detail()));
            m.put("status", item.status().name());
            raw.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("todos", raw);
        try {
            Files.writeString(tmp, json.write(root), StandardCharsets.UTF_8);
            move(tmp, file);
        } catch (IOException e) {
            throw new DomainError("todo.write.failed", "待办写入失败: " + file, e);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Todo fromMap(RoleId owner, Map<?, ?> map) {
        String title = str(map.get("title"));
        if (Text.isBlank(title)) {
            return null;
        }
        String rawId = str(map.get("id"));
        TodoId id = new TodoId(Text.isBlank(rawId) ? newId().value() : rawId);
        return new Todo(id, owner, title, Text.orEmpty(str(map.get("detail"))), parseStatus(map.get("status")));
    }

    private static TodoStatus parseStatus(Object value) {
        String name = str(value).trim();
        for (TodoStatus s : TodoStatus.values()) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return TodoStatus.PENDING;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static TodoId newId() {
        return new TodoId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    private static void requireOwner(RoleId owner) {
        if (owner == null) {
            throw new DomainError("todo.owner.null", "待办归属角色不能为空");
        }
    }

    private ReentrantLock lock(RoleId owner) {
        return locks.computeIfAbsent(owner.value(), k -> new ReentrantLock());
    }
}
