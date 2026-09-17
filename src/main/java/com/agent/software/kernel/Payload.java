package com.agent.software.kernel;

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
        this.values = Map.copyOf(values);
    }

    public static Payload empty() {
        return new Payload(Map.of());
    }

    /** 单键负载。 */
    public static Payload of(String key, Object value) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 追加/覆盖一个键，返回新实例。 */
    public Payload with(String key, Object value) {
        throw new UnsupportedOperationException("skeleton");
    }

    public Optional<String> string(String key) {
        throw new UnsupportedOperationException("skeleton");
    }

    public String stringOr(String key, String fallback) {
        throw new UnsupportedOperationException("skeleton");
    }

    public OptionalInt intValue(String key) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 仅供持久化 / JSON 适配器使用。 */
    public Map<String, Object> asMap() {
        return values;
    }

    // ── 常用键的语义化访问器，避免各处硬编码 key ──────────────────────

    /** 事件标题（事件转任务时用作描述）。 */
    public String title() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 正文/消息文本。 */
    public String text() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 邮件主题等。 */
    public String subject() {
        throw new UnsupportedOperationException("skeleton");
    }
}
