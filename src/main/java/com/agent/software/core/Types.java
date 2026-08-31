package com.agent.software.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 核心数据类型 (Python 版 types.py 的 Java 对应物).
 */
public final class Types {

    private Types() {
    }

    /** Agent 生命周期状态枚举. */
    public enum AgentState {
        OFF_DUTY("OFF_DUTY"),       // 下班 — context flushed, not processing
        ON_DUTY_IDLE("ON_DUTY_IDLE"), // 上班空闲 — alive, listening for events
        ON_DUTY_BUSY("ON_DUTY_BUSY"), // 上班忙碌 — executing a workflow
        WRAPPING_UP("WRAPPING_UP"),  // 收尾中 — finishing last task before shift end
        WAIT("WAIT");                // 等待中 — talk wait=true 同步等待对方回复

        public final String value;

        AgentState(String value) {
            this.value = value;
        }

        public static AgentState from(String value) {
            for (AgentState s : values()) {
                if (s.value.equals(value)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("未知 AgentState: " + value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /** 事件优先级: 数值越大越紧急. LOW=1, NORMAL=3, HIGH=6, EMERGENCY=10. */
    public enum Priority {
        LOW(1), NORMAL(3), HIGH(6), EMERGENCY(10);

        public final int value;

        Priority(int value) {
            this.value = value;
        }

        public static Priority from(int value) {
            for (Priority p : values()) {
                if (p.value == value) {
                    return p;
                }
            }
            return NORMAL;
        }

        @Override
        public String toString() {
            return name();
        }
    }

    /** 失败文本谓词: 统一错误前缀约定 (is_failure_text). */
    public static boolean isFailureText(String s) {
        return s.startsWith("[exit") || s.startsWith("错误")
                || s.startsWith("文件不存在") || s.startsWith("目录不存在");
    }

    /** 标准化事件 (types.py 的 Event). */
    public static final class Event {
        public String id;
        public String source = "";        // e.g. "github", "email", "slack", "cron", "time", "task"
        public String eventType = "";     // e.g. "new_pr", "mention", "alert", "SHIFT_START", "TASK_DUE"
        public Priority priority = Priority.NORMAL;
        public Map<String, Object> payload = new LinkedHashMap<>();
        public Instant timestamp = Instant.now();
        /** 定向投递: 只发给指定 role_id (null = 广播给所有角色). */
        public String targetRole = null;
        /** 触发 Tick: null = 立即触发; 整数 = 在指定绝对 Tick 触发. */
        public Integer triggerTick = null;

        public Event() {
            this.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }

        public Event(String source, String eventType, Priority priority) {
            this();
            this.source = source;
            this.eventType = eventType;
            this.priority = priority;
        }

        public Event(String source, String eventType, Priority priority,
                     Map<String, Object> payload, String targetRole) {
            this(source, eventType, priority);
            if (payload != null) {
                this.payload = payload;
            }
            this.targetRole = targetRole;
        }

        public Event copy() {
            Event e = new Event();
            e.id = this.id;
            e.source = this.source;
            e.eventType = this.eventType;
            e.priority = this.priority;
            e.payload = this.payload == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(this.payload);
            e.timestamp = this.timestamp;
            e.targetRole = this.targetRole;
            e.triggerTick = this.triggerTick;
            return e;
        }

        @Override
        public String toString() {
            return "Event(" + id + ", " + source + "/" + eventType
                    + ", " + priority + (targetRole == null ? "" : ", target=" + targetRole) + ")";
        }
    }
}
