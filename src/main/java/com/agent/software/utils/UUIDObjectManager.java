package com.agent.software.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * UUID 实体容器：按 uuid 增删查，并提供一个只读视图。
 *
 * <p>底层用 {@link CopyOnWriteArrayList}：运行期抽调/招人会在角色 worker 正在遍历时写集合，
 * 普通 ArrayList 会抛 {@code ConcurrentModificationException}。这个容器读多写少，正好合适。
 *
 * <p>线程契约：{@link #add}/{@link #remove}/{@link #clear} 可被任意线程调用；
 * {@link #all()} 返回快照，遍历它不会受并发写影响。
 */
public abstract class UUIDObjectManager<T extends UUIDObject> {

    protected final List<T> values;

    public UUIDObjectManager() {
        this.values = new CopyOnWriteArrayList<>();
    }

    public void add(T value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        this.values.add(value);
    }

    public boolean remove(T value) {
        return this.values.remove(value);
    }

    public T removeByUUID(String uuid) {
        T found = findObjectByUUID(uuid);
        if (found != null) {
            this.values.remove(found);
        }
        return found;
    }

    public boolean contains(T value) {
        return this.values.contains(value);
    }

    public boolean containsUUID(String uuid) {
        return findObjectByUUID(uuid) != null;
    }

    /** 按 uuid 查找；找不到返回 {@code null}。 */
    public T findObjectByUUID(String uuid) {
        if (uuid == null) {
            return null;
        }
        for (T value : this.values) {
            if (uuid.equals(value.uuid)) {
                return value;
            }
        }
        return null;
    }

    public T find(Predicate<T> predicate) {
        for (T value : this.values) {
            if (predicate.test(value)) {
                return value;
            }
        }
        return null;
    }

    public List<T> findAll(Predicate<T> predicate) {
        List<T> out = new ArrayList<>();
        for (T value : this.values) {
            if (predicate.test(value)) {
                out.add(value);
            }
        }
        return out;
    }

    /** 只读快照：调用方拿到后可以安全遍历。 */
    public List<T> all() {
        return List.copyOf(this.values);
    }

    public int size() {
        return this.values.size();
    }

    public boolean isEmpty() {
        return this.values.isEmpty();
    }

    public void clear() {
        this.values.clear();
    }
}
