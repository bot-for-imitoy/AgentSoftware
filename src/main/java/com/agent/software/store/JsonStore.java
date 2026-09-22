package com.agent.software.store;

import com.agent.software.utils.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多文件 JSON 配置读取：按优先级顺序读取，后读的覆盖先读的同名键，未出现的键保留。
 *
 * <p>与 {@code providers.default.json + providers.local.json} 的覆盖方式一致。
 */
public class JsonStore {

    private final List<Path> files;
    private final Map<String, Object> merged = new LinkedHashMap<>();

    public JsonStore(List<Path> filesInPriorityOrder) {
        this.files = filesInPriorityOrder == null ? List.of() : List.copyOf(filesInPriorityOrder);
    }

    public void load() {
        merged.clear();
        for (Path p : files) {
            if (p == null || !Files.exists(p)) {
                continue;
            }
            Map<String, Object> content = Json.readFile(p);
            deepMerge(merged, content);
        }
    }

    /** 取某个顶层键的对象值；不是对象/不存在时返回空 Map。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String key) {
        Object v = merged.get(key);
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new LinkedHashMap<>();
    }

    /** 取某个顶层键下的 "name → object" 表（如 role_templates）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> section(String key) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Object v = merged.get(key);
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> child) {
                    out.put(String.valueOf(e.getKey()), (Map<String, Object>) child);
                }
            }
        }
        return out;
    }

    public List<Path> files() {
        return files;
    }

    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> e : source.entrySet()) {
            Object existing = target.get(e.getKey());
            Object incoming = e.getValue();
            if (existing instanceof Map && incoming instanceof Map) {
                deepMerge((Map<String, Object>) existing, (Map<String, Object>) incoming);
            } else {
                target.put(e.getKey(), incoming);
            }
        }
    }

    /** 便捷构造：一批路径。 */
    public static JsonStore of(Path... files) {
        List<Path> list = new ArrayList<>();
        for (Path p : files) {
            if (p != null) {
                list.add(p);
            }
        }
        JsonStore store = new JsonStore(list);
        store.load();
        return store;
    }
}
