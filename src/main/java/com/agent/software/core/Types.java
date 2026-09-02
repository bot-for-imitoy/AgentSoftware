package com.agent.software.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Core data types (the Java counterpart of the Python types.py).
 */
public final class Types {

    private Types() {
    }

    /** Agent lifecycle state enum. */
    public enum AgentState {
        OFF_DUTY("OFF_DUTY"),       // off duty — context flushed, not processing
        ON_DUTY_IDLE("ON_DUTY_IDLE"), // on duty, idle — alive, listening for events
        ON_DUTY_BUSY("ON_DUTY_BUSY"), // on duty, busy — executing a workflow
        WRAPPING_UP("WRAPPING_UP"),  // wrapping up — finishing the last task before shift end
        WAIT("WAIT");                // waiting — talk wait=true, synchronously waiting for the other party's reply

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
            throw new IllegalArgumentException("Unknown AgentState: " + value);
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /** Event priority: higher value is more urgent. LOW=1, NORMAL=3, HIGH=6, EMERGENCY=10. */
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

    /** Failure-text predicate: unified error prefix convention (is_failure_text). */
    public static boolean isFailureText(String s) {
        return s.startsWith("[exit") || s.startsWith("Error")
                || s.startsWith("error") || s.startsWith("File not found")
                || s.startsWith("Directory not found");
    }

    /** Normalized event (the Event of types.py). */
    public static final class Event {
        public String id;
        public String source = "";        // e.g. "github", "email", "slack", "cron", "time", "task"
        public String eventType = "";     // e.g. "new_pr", "mention", "alert", "SHIFT_START", "TASK_DUE"
        public Priority priority = Priority.NORMAL;
        public Map<String, Object> payload = new LinkedHashMap<>();
        public Instant timestamp = Instant.now();
        /** Directed delivery: only sent to the given role_id (null = broadcast to all roles). */
        public String targetRole = null;
        /** Trigger tick: null = fire immediately; an integer = fire at the given absolute tick. */
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
