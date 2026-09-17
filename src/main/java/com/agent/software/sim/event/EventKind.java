package com.agent.software.sim.event;

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

    public EventKind {
        source = source == null ? "" : source;
        name = name == null ? "" : name;
    }

    /** 供日志与持久化使用的 "source/name" 文本。 */
    public String wire() {
        return source + "/" + name;
    }

    /** 由 "source/name" 文本还原；不含分隔符时视为无来源。 */
    public static EventKind parse(String wire) {
        if (wire == null || wire.isBlank()) {
            return new EventKind("", "");
        }
        int idx = wire.indexOf('/');
        return idx < 0
                ? new EventKind("", wire)
                : new EventKind(wire.substring(0, idx), wire.substring(idx + 1));
    }

    @Override
    public String toString() {
        return wire();
    }
}
