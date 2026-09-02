package com.agent.software.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON utilities (the Java counterpart of the Python json module).
 *
 * The whole project uses this class uniformly for JSON parsing/serialization to avoid ObjectMapper
 * instance drift. All object mappings use {@code Map<String,Object>} / {@code List<Object>} structures,
 * consistent with Python's dict/list semantics.
 */
public final class Json {

    // Note: do not enable INDENT_OUTPUT on the mapper - that would make stringify() (compact, single-line)
    // also output multi-line indented JSON, breaking MCP stdio's newline-delimited JSON transport. Where
    // indentation is needed, use stringifyPretty() explicitly.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /** Parse JSON text into a Map (the root node must be an object). */
    public static Map<String, Object> parseObject(String text) throws IOException {
        return MAPPER.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    /** Parse JSON text into any structure (List/Map/String/Number/Boolean/null). */
    public static Object parse(String text) throws IOException {
        return MAPPER.readValue(text, Object.class);
    }

    /** Serialize to compact JSON (ensure_ascii=False semantics: keep UTF-8). */
    public static String stringify(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    /** Serialize to indented JSON (for writing configs/archives to disk). */
    public static String stringifyPretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    /** Read the entire file (UTF-8). */
    public static String readFile(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Atomically write a file: write to .tmp first, then rename (same as the Python tmp+replace approach). */
    public static void atomicWrite(Path path, String content) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Path tmp = path.resolveSibling("." + path.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Get a value by dotted path (semantics of the Python ConfigStore.get): "llm.model" → drill down level by level. */
    @SuppressWarnings("unchecked")
    public static Object getByPath(Map<String, Object> root, String dotPath, Object def) {
        Object current = root;
        for (String part : dotPath.split("\\.")) {
            if (!(current instanceof Map)) {
                return def;
            }
            Map<String, Object> m = (Map<String, Object>) current;
            if (!m.containsKey(part)) {
                return def;
            }
            current = m.get(part);
        }
        return deepCopy(current);
    }

    /** Deep-copy a JSON-compatible value (semantics of Python json.loads(json.dumps(v))). */
    public static Object deepCopy(Object value) {
        try {
            return parse(stringify(value));
        } catch (IOException e) {
            return value;
        }
    }

    /** Value to string (Map/List via JSON, everything else via toString). */
    @SuppressWarnings("unchecked")
    public static String asText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Map || value instanceof List) {
            return stringify(value);
        }
        return String.valueOf(value);
    }

    /** Read a string field from a Map. */
    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    /** Read an int field from a Map. */
    public static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Read a double field from a Map. */
    public static double doubleVal(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Read a boolean field from a Map (accepts "true"/"1"/"yes" etc.). */
    public static boolean boolVal(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("on")) {
            return true;
        }
        if (s.equals("0") || s.equals("false") || s.equals("no") || s.equals("off")) {
            return false;
        }
        return def;
    }

    /** Read a string list from a Map (returns an empty list if missing / not a list). */
    @SuppressWarnings("unchecked")
    public static List<String> strList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }
}
