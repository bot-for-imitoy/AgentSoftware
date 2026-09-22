package com.agent.software.event;

import com.agent.software.utils.Data;
import com.agent.software.utils.DataRegistry;
import com.agent.software.utils.Json;
import com.agent.software.utils.UUIDObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件：从某个角色发往某个角色（或广播），可带触发时间。
 *
 * <p>设计约束（v3）：
 * <ul>
 *   <li>只用 id 字符串引用角色（{@code fromRoleId/targetRoleId}），不持有 Role 对象，
 *       这样事件是纯数据、可落库、不会出现"角色已移出但事件还指着它"的悬空引用。</li>
 *   <li>事件 id 用继承来的 {@link UUIDObject#uuid}，不再另设 {@code id} 字段。</li>
 *   <li>字段不是 final：持久化恢复是"先造空实例、再 {@link #loadData(Map)} 回填"（报备项 B1）。</li>
 * </ul>
 */
public class Event extends UUIDObject implements Data {

    public static final String DATA_TYPE = "event";

    static {
        DataRegistry.register(DATA_TYPE, () -> new Event(null, null, 0L, ""));
    }

    public String fromRoleId;
    public String targetRoleId;      // null = 广播
    public long targetTime;          // 绝对 tick
    public String content;
    public EventType type = EventType.CUSTOM;
    public Priority priority = Priority.NORMAL;
    public Map<String, Object> payload = new LinkedHashMap<>();
    public long createdAt;
    public String source = "";

    public Event(String fromRoleId, String targetRoleId, long targetTime, String content) {
        super();
        this.fromRoleId = fromRoleId;
        this.targetRoleId = targetRoleId;
        this.targetTime = targetTime;
        this.content = content == null ? "" : content;
        this.createdAt = targetTime;
    }

    public Event(String uuid, String fromRoleId, String targetRoleId, long targetTime, String content) {
        super(uuid);
        this.fromRoleId = fromRoleId;
        this.targetRoleId = targetRoleId;
        this.targetTime = targetTime;
        this.content = content == null ? "" : content;
        this.createdAt = targetTime;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 静态构建器：修掉了原骨架里"非静态内部类 + 构造器 protected 导致无法实例化"的问题。 */
    public static final class Builder {
        private String uuid;
        private String fromRoleId;
        private String targetRoleId;
        private long targetTime;
        private String content = "";
        private EventType type = EventType.CUSTOM;
        private Priority priority = Priority.NORMAL;
        private Map<String, Object> payload = new LinkedHashMap<>();
        private String source = "";

        public Builder from(String roleId) {
            this.fromRoleId = roleId;
            return this;
        }

        public Builder to(String roleId) {
            this.targetRoleId = roleId;
            return this;
        }

        public Builder at(long tick) {
            this.targetTime = tick;
            return this;
        }

        public Builder content(String c) {
            this.content = c == null ? "" : c;
            return this;
        }

        public Builder type(EventType t) {
            this.type = t == null ? EventType.CUSTOM : t;
            return this;
        }

        public Builder priority(Priority p) {
            this.priority = p == null ? Priority.NORMAL : p;
            return this;
        }

        public Builder payload(Map<String, Object> m) {
            this.payload = m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
            return this;
        }

        public Builder uuid(String uuid) {
            this.uuid = uuid;
            return this;
        }

        public Builder source(String s) {
            this.source = s == null ? "" : s;
            return this;
        }

        public Event build() {
            Event e = uuid == null
                    ? new Event(fromRoleId, targetRoleId, targetTime, content)
                    : new Event(uuid, fromRoleId, targetRoleId, targetTime, content);
            e.type = type;
            e.priority = priority;
            e.payload = payload;
            e.source = source;
            return e;
        }
    }

    public boolean isBroadcast() {
        return targetRoleId == null || targetRoleId.isEmpty();
    }

    public boolean isDue(long now) {
        return targetTime <= now;
    }

    public boolean targetedAt(String roleId) {
        return !isBroadcast() && targetRoleId.equals(roleId);
    }

    @Override
    public Map<String, String> getData() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("data_type", type());
        d.put("uuid", uuid);
        d.put("from_role_id", fromRoleId == null ? "" : fromRoleId);
        d.put("target_role_id", targetRoleId == null ? "" : targetRoleId);
        d.put("target_time", Long.toString(targetTime));
        d.put("content", content == null ? "" : content);
        d.put("event_type", type.name());
        d.put("priority", priority.name());
        d.put("source", source == null ? "" : source);
        d.put("created_at", Long.toString(createdAt));
        d.put("payload", Json.stringify(payload == null ? new LinkedHashMap<>() : payload));
        return d;
    }

    @Override
    public void loadData(Map<String, String> data) {
        if (data == null) {
            return;
        }
        if (data.containsKey("uuid")) {
            this.uuid = data.get("uuid");
        }
        this.fromRoleId = emptyToNull(data.get("from_role_id"));
        this.targetRoleId = emptyToNull(data.get("target_role_id"));
        this.targetTime = parseLong(data.get("target_time"), 0L);
        this.content = data.getOrDefault("content", "");
        this.type = EventType.from(data.get("event_type"));
        this.priority = Priority.from(data.get("priority"));
        this.source = data.getOrDefault("source", "");
        this.createdAt = parseLong(data.get("created_at"), this.targetTime);
        String payloadJson = data.get("payload");
        this.payload = payloadJson == null || payloadJson.isBlank()
                ? new LinkedHashMap<>() : Json.parseObject(payloadJson);
    }

    /** 持久化/注册表用的类型标记；Task 覆盖为 "task"。 */
    public String type() {
        return DATA_TYPE;
    }

    protected static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    protected static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    protected static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public String toString() {
        return "Event(" + type + ", " + (fromRoleId == null ? "-" : fromRoleId) + "→"
                + (isBroadcast() ? "*" : targetRoleId) + ", t=" + targetTime + ")";
    }
}
