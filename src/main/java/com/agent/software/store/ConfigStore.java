package com.agent.software.store;

import com.agent.software.utils.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON config storage (Python version config_store.py).
 *
 * Config is saved as a JSON object, supporting access to multi-level keys via dot paths, e.g.
 * {@code store.set("llm.model", "gpt-4o-mini")}.
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

    /** Path of the config file. */
    public Path getPath() {
        return path;
    }

    /** A copy of the current config (after modifying, save it via set/update). */
    public Map<String, Object> data() {
        return new LinkedHashMap<>(data);
    }

    public boolean exists() {
        return Files.exists(path);
    }

    /** Load config from the file; use an empty config when the file does not exist. */
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
            throw new RuntimeException("Failed to read config file: " + path, e);
        }
        Object loaded;
        try {
            loaded = Json.parse(text);
        } catch (IOException e) {
            throw new IllegalArgumentException("Config file is not valid JSON: " + path, e);
        }
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException("Config file root must be a JSON object: " + path);
        }
        data.clear();
        data.putAll((Map<String, Object>) loaded);
        return data();
    }

    /** Atomically write the current config to the file. */
    public Path save() {
        try {
            Json.atomicWrite(path, Json.stringifyPretty(data));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config: " + path, e);
        }
        return path;
    }

    /** Read the value at the dot path; returns default when it does not exist. */
    public Object get(String key, Object def) {
        return Json.getByPath(data, key, def);
    }

    /** Create or overwrite a key, and save immediately. */
    public Object set(String key, Object value) {
        Object copied = Json.deepCopy(value);
        setByPath(data, key, copied);
        save();
        return Json.deepCopy(copied);
    }

    /** Batch create or overwrite keys; the keys of values also support dot paths. */
    public Map<String, Object> update(Map<String, Object> values) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            setByPath(data, e.getKey(), Json.deepCopy(e.getValue()));
        }
        save();
        return data();
    }

    /** Delete a key; returns true when the key exists and is deleted successfully. */
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
                throw new IllegalArgumentException("Intermediate node in key path is not an object: " + key);
            }
            current = (Map<String, Object>) child;
        }
        current.put(parts[parts.length - 1], value);
    }
}
