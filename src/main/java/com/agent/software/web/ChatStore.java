package com.agent.software.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Web 消息存储：群聊 / 客户会话 / 系统通知的统一数据源。
 *
 * <p>只做记录与读取；客户会话的等待协调在 {@code client.ClientChannel}。
 */
public class ChatStore {

    public static final String KIND_TALK = "talk";
    public static final String KIND_CLIENT = "client";
    public static final String KIND_NOTE = "note";
    public static final String CLIENT_NAME = "Client A";

    /** 一条 Web 消息。 */
    public static final class ChatMessage {
        public long seq;
        public String kind;
        public String group;
        public String fromRoleId;
        public String fromName;
        public String targetRoleId;
        public String targetName;
        public String text;
        public String urgency;
        public long timestamp;
    }

    private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    public ChatMessage record(String kind, String group, String fromRoleId, String fromName,
                              String targetRoleId, String targetName, String text, String urgency) {
        ChatMessage m = new ChatMessage();
        m.seq = seq.incrementAndGet();
        m.kind = kind == null ? "" : kind;
        m.group = group == null ? "" : group;
        m.fromRoleId = fromRoleId == null ? "" : fromRoleId;
        m.fromName = fromName == null ? "" : fromName;
        m.targetRoleId = targetRoleId == null ? "" : targetRoleId;
        m.targetName = targetName == null ? "" : targetName;
        m.text = text == null ? "" : text;
        m.urgency = urgency == null ? "" : urgency;
        m.timestamp = System.currentTimeMillis();
        messages.add(m);
        return m;
    }

    public long lastSeq() {
        return seq.get();
    }

    public List<Map<String, Object>> messagesSince(long sinceSeq) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m.seq > sinceSeq) {
                out.add(toMap(m));
            }
        }
        return out;
    }

    /** 客户端提交一条回复（同时记录成一条 client 消息）。 */
    public ChatMessage postClientReply(String text) {
        return record(KIND_CLIENT, "", "", CLIENT_NAME, "", "", text, "");
    }

    public static Map<String, Object> toMap(ChatMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("seq", m.seq);
        map.put("kind", m.kind);
        map.put("group", m.group);
        map.put("from_role_id", m.fromRoleId);
        map.put("from_name", m.fromName);
        map.put("target_role_id", m.targetRoleId);
        map.put("target_name", m.targetName);
        map.put("text", m.text);
        map.put("urgency", m.urgency);
        map.put("timestamp", m.timestamp);
        return map;
    }
}
