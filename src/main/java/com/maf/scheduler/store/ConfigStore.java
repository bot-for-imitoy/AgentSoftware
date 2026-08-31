package com.maf.scheduler.store;

import com.maf.scheduler.core.PathManager;
import com.maf.scheduler.utils.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON 配置存储 (Python 版 config_store.py).
 *
 * 配置以 JSON 对象保存, 支持通过点号路径访问多级键, 例如
 * {@code store.set("llm.model", "deepseek-chat")}.
 */
public class ConfigStore {

    private final Path path;
    private final Map<String, Object> data = new LinkedHashMap<>();

    public ConfigStore(Path path) {
        this.path = path;
        load();
    }

    public ConfigStore() {
        this(PathManager.createDefault().configFile("config.json"));
    }

    /** 配置文件路径. */
    public Path getPath() {
        return path;
    }

    /** 当前配置的副本 (修改后请通过 set/update 保存). */
    public Map<String, Object> data() {
        return new LinkedHashMap<>(data);
    }

    public boolean exists() {
        return Files.exists(path);
    }

    /** 从文件加载配置; 文件不存在时使用空配置. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> load() {
        if (!Files.exists(path)) {
            data.clear();
            return data();
        }
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException("读取配置文件失败: " + path, e);
        }
        Object loaded;
        try {
            loaded = Json.parse(text);
        } catch (IOException e) {
            throw new IllegalArgumentException("配置文件不是有效 JSON: " + path, e);
        }
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException("配置文件根节点必须是 JSON 对象: " + path);
        }
        data.clear();
        data.putAll((Map<String, Object>) loaded);
        return data();
    }

    /** 将当前配置原子写入文件. */
    public Path save() {
        try {
            Json.atomicWrite(path, Json.stringifyPretty(data));
        } catch (IOException e) {
            throw new RuntimeException("保存配置失败: " + path, e);
        }
        return path;
    }

    /** 读取点号路径对应的值, 不存在时返回 default. */
    public Object get(String key, Object def) {
        return Json.getByPath(data, key, def);
    }

    /** 创建或覆盖一个键, 并立即保存. */
    public Object set(String key, Object value) {
        Object copied = Json.deepCopy(value);
        setByPath(data, key, copied);
        save();
        return Json.deepCopy(copied);
    }

    /** 批量创建或覆盖键; values 的键同样支持点号路径. */
    public Map<String, Object> update(Map<String, Object> values) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            setByPath(data, e.getKey(), Json.deepCopy(e.getValue()));
        }
        save();
        return data();
    }

    /** 删除键; 键存在且删除成功时返回 true. */
    public boolean delete(String key) {
        String[] parts = key.split("\\.");
        Map<String, Object> current = data;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = current.get(parts[i]);
            if (!(child instanceof Map)) {
                return false;
            }
            current = (Map<String, Object>) child;
        }
        if (!current.containsKey(parts[parts.length - 1])) {
            return false;
        }
        current.remove(parts[parts.length - 1]);
        save();
        return true;
    }

    @SuppressWarnings("unchecked")
    private static void setByPath(Map<String, Object> root, String key, Object value) {
        String[] parts = key.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = current.get(parts[i]);
            if (child == null) {
                child = new LinkedHashMap<String, Object>();
                current.put(parts[i], child);
            } else if (!(child instanceof Map)) {
                throw new IllegalArgumentException("键路径中间节点不是对象: " + key);
            }
            current = (Map<String, Object>) child;
        }
        current.put(parts[parts.length - 1], value);
    }
}
