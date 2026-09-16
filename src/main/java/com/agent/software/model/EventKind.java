package com.agent.software.model;

/**
 * 事件类型 =（来源, 名称）。
 *
 * <p>替代 master 中裸 {@code String source} + {@code String eventType}；常用类型在此集中声明，
 * 外部系统仍可构造新的 EventKind。
 */
public record EventKind(String source, String name) {

    public static final EventKind SHIFT_START = new EventKind("time", "SHIFT_START");
    public static final EventKind SHIFT_END = new EventKind("time", "SHIFT_END");
    public static final EventKind TASK_DUE = new EventKind("task", "TASK_DUE");
    public static final EventKind NEW_MAIL = new EventKind("email", "NEW_MAIL");

    /** 供日志与持久化使用的 "source/name" 文本。 */
    public String wire() {
        throw new UnsupportedOperationException("skeleton");
    }
}
