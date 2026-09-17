package com.agent.software.kernel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 不可变、带类型访问器的事件/任务负载。
 *
 * <p>替代裸 {@code Map<String,Object>} 跨层传递；{@link #asMap()} 只允许在
 * 适配器（JSON 编解码）边界调用。
 */
public final class Payload {

    private final Map<String, Object> values;

    private Payload(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                copy.put(e.getKey(), e.getValue());
            }
        }
        this.values = Collections.unmodifiableMap(copy);
    }

    public static Payload empty() {
        return new Payload(Map.of());
    }

    /** 单键负载。 */
    public static Payload of(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return new Payload(m);
    }

    /** 由裸 Map 构造（仅允许在 JSON / 持久化边界调用）。 */
    public static Payload ofMap(Map<String, Object> values) {
        return new Payload(values == null ? Map.of() : values);
    }

    /** 追加/覆盖一个键，返回新实例。 */
    public Payload with(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(values);
        m.put(key, value);
        return new Payload(m);
    }

    public Optional<String> string(String key) {
        Object v = values.get(key);
        if (v == null) {
            return Optional.empty();
        }
        String s = v instanceof String str ? str : String.valueOf(v);
        return Optional.of(s);
    }

    public String stringOr(String key, String fallback) {
        return string(key).orElse(fallback);
    }

    public OptionalInt intValue(String key) {
        Object v = values.get(key);
        if (v instanceof Number n) {
            return OptionalInt.of(n.intValue());
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return OptionalInt.of((int) Double.parseDouble(s.trim()));
            } catch (NumberFormatException ignored) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.empty();
    }

    /** 布尔读取（接受 true/false、1/0、yes/no）。 */
    public boolean boolOr(String key, boolean fallback) {
        Object v = values.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return fallback;
        }
        String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> fallback;
        };
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** 仅供持久化 / JSON 适配器使用。 */
    public Map<String, Object> asMap() {
        return values;
    }

    // ── 常用键的语义化访问器，避免各处硬编码 key ──────────────────────

    /** 事件标题（事件转任务时用作描述）。 */
    public String title() {
        return stringOr("title", "");
    }

    /** 正文/消息文本。 */
    public String text() {
        return stringOr("text", "");
    }

    /** 邮件主题等。 */
    public String subject() {
        return stringOr("subject", "");
    }
}
