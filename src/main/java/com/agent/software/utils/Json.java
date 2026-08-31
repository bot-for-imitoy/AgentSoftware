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
 * JSON 工具 (Python json 模块的 Java 对应物).
 *
 * 全项目统一使用本类做 JSON 解析/序列化, 避免各处 ObjectMapper 实例漂移.
 * 所有对象映射采用 {@code Map<String,Object>} / {@code List<Object>} 结构,
 * 与 Python 的 dict/list 语义一致.
 */
public final class Json {

    // 注意: 不在 mapper 上开启 INDENT_OUTPUT — 那会让 stringify() (紧凑, 单行) 也输出
    // 多行缩进 JSON, 破坏 MCP stdio 的 newline-delimited JSON 传输. 需要缩进的地方
    // 显式使用 stringifyPretty().
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /** 解析 JSON 文本 → Map (根节点必须是对象). */
    public static Map<String, Object> parseObject(String text) throws IOException {
        return MAPPER.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    /** 解析 JSON 文本 → 任意结构 (List/Map/String/Number/Boolean/null). */
    public static Object parse(String text) throws IOException {
        return MAPPER.readValue(text, Object.class);
    }

    /** 序列化为紧凑 JSON (ensure_ascii=False 语义: 保留 UTF-8). */
    public static String stringify(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    /** 序列化为缩进 JSON (配置/存档写盘用). */
    public static String stringifyPretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    /** 读取整个文件 (UTF-8). */
    public static String readFile(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 原子写文件: 先写 .tmp 再 rename (与 Python 版 tmp+replace 一致). */
    public static void atomicWrite(Path path, String content) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Path tmp = path.resolveSibling("." + path.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** 点号路径取值 (Python 版 ConfigStore.get 的语义): "llm.model" → 逐级下钻. */
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

    /** 深度复制 JSON 兼容值 (Python json.loads(json.dumps(v)) 语义). */
    public static Object deepCopy(Object value) {
        try {
            return parse(stringify(value));
        } catch (IOException e) {
            return value;
        }
    }

    /** 值 → 字符串 (Map/List 走 JSON, 其余走 toString). */
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

    /** 从 Map 读字符串字段. */
    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    /** 从 Map 读 int 字段. */
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

    /** 从 Map 读 double 字段. */
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

    /** 从 Map 读 boolean 字段 (兼容 "true"/"1"/"yes" 等). */
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

    /** 从 Map 读字符串列表 (缺失/非列表返回空列表). */
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
