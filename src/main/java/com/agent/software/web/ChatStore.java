package com.agent.software.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天消息存储 + 与甲方对话协调 (Web 界面数据源).
 *
 * <p>每套 {@link com.agent.software.AgentSystem} 直接持有自己的 ChatStore 实例,
 * 记录两类消息:
 * <ul>
 *   <li>{@code talk}  — 组内角色间 talk 消息 (TalkTo 投递成功后记录);</li>
 *   <li>{@code client} — 领导组成员与甲方(用户)的对话 (talk_to_client).</li>
 * </ul>
 * 消息带单调递增的 {@code seq} 序号, Web 前端按 {@code since} 增量拉取.
 *
 * <p>甲方对话协调: 领导组成员调用 talk_to_client 且 Web 界面已挂载时,
 * {@link #beginClientWait} 置为"等待甲方回复"状态 (Web 前端据此启用输入框),
 * 阻塞等待 {@link #postClientReply} 提交的回复, 超时返回 null.
 * 未挂载 Web 时保持原控制台 (System.in) 交互, 行为不变.
 */
public final class ChatStore {

    /** 历史消息上限 (超出丢弃最旧, 防止内存无限膨胀). */
    public static final int MAX_HISTORY = 5000;

    /** 消息类型: 组内角色间 talk. */
    public static final String KIND_TALK = "talk";
    /** 消息类型: 与甲方(用户)对话. */
    public static final String KIND_CLIENT = "client";

    /** 甲方在聊天记录中的显示名. */
    public static final String CLIENT_NAME = "甲方";

    /** 一条聊天消息 (字段全公开, 便于序列化为 JSON Map). */
    public static final class ChatMessage {
        public long seq;
        public long ts;            // epoch millis
        public String kind;        // talk / client
        public String group;       // 所属组 (英文名), 前端按组过滤
        public String fromRoleId;  // 发送者 role_id (甲方为空串)
        public String fromName;    // 发送者人名
        public String toRoleId;    // 接收者 role_id
        public String toName;      // 接收者人名
        public String text;        // 消息内容
        public String urgency;     // talk 消息的紧急度 (可空)
    }

    private final List<ChatMessage> history = new ArrayList<>();
    private long lastSeq = 0;

    // ── 甲方对话协调状态 (synchronized on this) ──────────────
    private boolean pendingClientRequest = false;   // 是否正等待甲方回复
    private String pendingHolderRoleId = null;
    private String pendingHolderName = null;
    private String pendingGroup = null;             // 发起对话的成员所属组
    private boolean clientRequestDone = false;
    private String pendingClientReply = null;

    // ── Web 挂载心跳 (volatile, 无锁) ────────────────────────
    private volatile long lastAttachMillis = 0L;
    /** 心跳 TTL: 该时长内有 Web 前端轮询过即视为已挂载. */
    public static final long ATTACH_TTL_MS = 60_000L;

    // ── 消息记录 ─────────────────────────────────────────────

    /**
     * 记录一条聊天消息. 线程安全.
     *
     * @return 已记录的消息对象 (含分配好的 seq).
     */
    public ChatMessage record(String kind, String group, String fromRoleId, String fromName,
                              String toRoleId, String toName, String text, String urgency) {
        ChatMessage m = new ChatMessage();
        m.ts = System.currentTimeMillis();
        m.kind = kind;
        m.group = group == null ? "" : group;
        m.fromRoleId = fromRoleId == null ? "" : fromRoleId;
        m.fromName = fromName == null ? "" : fromName;
        m.toRoleId = toRoleId == null ? "" : toRoleId;
        m.toName = toName == null ? "" : toName;
        m.text = text == null ? "" : text;
        m.urgency = urgency;
        synchronized (history) {
            m.seq = ++lastSeq;
            history.add(m);
            while (history.size() > MAX_HISTORY) {
                history.remove(0);
            }
        }
        return m;
    }

    /** 当前最大 seq (前端增量拉取水位). */
    public long lastSeq() {
        synchronized (history) {
            return lastSeq;
        }
    }

    /** 返回 seq &gt; sinceSeq 的全部消息 (按 seq 升序), 序列化为 JSON Map 列表. */
    public List<Map<String, Object>> messagesSince(long sinceSeq) {
        synchronized (history) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (ChatMessage m : history) {
                if (m.seq > sinceSeq) {
                    out.add(toMap(m));
                }
            }
            return out;
        }
    }

    /** 消息 → JSON Map. */
    public static Map<String, Object> toMap(ChatMessage m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seq", m.seq);
        out.put("ts", m.ts);
        out.put("kind", m.kind);
        out.put("group", m.group);
        out.put("fromRoleId", m.fromRoleId);
        out.put("fromName", m.fromName);
        out.put("toRoleId", m.toRoleId);
        out.put("toName", m.toName);
        out.put("text", m.text);
        if (m.urgency != null && !m.urgency.isEmpty()) {
            out.put("urgency", m.urgency);
        }
        return out;
    }

    // ── 甲方对话协调 ─────────────────────────────────────────

    /**
     * 登记"正等待甲方回复" (Web 模式 talk_to_client 调用).
     *
     * @return null=成功; 否则错误描述 (已有其他成员在等待).
     */
    public synchronized String beginClientWait(String roleId, String name, String group) {
        if (pendingClientRequest) {
            return "another member " + pendingHolderName + " (" + pendingHolderRoleId
                    + ") is currently waiting for the client reply";
        }
        pendingClientRequest = true;
        pendingHolderRoleId = roleId;
        pendingHolderName = name;
        pendingGroup = group == null ? "" : group;
        clientRequestDone = false;
        pendingClientReply = null;
        notifyAll();
        return null;
    }

    /** 当前是否正等待甲方回复 (Web 前端输入框启用条件之一). */
    public synchronized boolean isClientWaitPending() {
        return pendingClientRequest;
    }

    /** 等待中的成员名 (无则 null). */
    public synchronized String pendingHolderName() {
        return pendingHolderName;
    }

    /** 等待中的成员 role_id (无则 null). */
    public synchronized String pendingHolderRoleId() {
        return pendingHolderRoleId;
    }

    /** 等待中的成员所属组 (无则 null). */
    public synchronized String pendingGroup() {
        return pendingGroup;
    }

    /**
     * 甲方提交回复 (Web 前端 POST /api/reply). 记录 client 消息并唤醒等待中的成员.
     *
     * @return 已记录的消息; 当前没有等待中的甲方对话时返回 null.
     */
    public synchronized ChatMessage postClientReply(String text) {
        if (!pendingClientRequest) {
            return null;
        }
        ChatMessage m = record(KIND_CLIENT, pendingGroup, "", CLIENT_NAME,
                pendingHolderRoleId, pendingHolderName, text, null);
        pendingClientReply = text;
        clientRequestDone = true;
        notifyAll();
        return m;
    }

    /**
     * 阻塞等待甲方回复 (Web 模式, 由 talk_to_client 调用).
     *
     * @param timeoutMs 最长等待毫秒数.
     * @return 甲方回复文本; 超时或中断返回 null.
     */
    public synchronized String awaitClientReply(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!clientRequestDone) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                return null;
            }
            wait(remain);
        }
        return pendingClientReply;
    }

    /** 结束等待状态 (回复已收到或超时), 由 talk_to_client 调用. */
    public synchronized void endClientWait() {
        pendingClientRequest = false;
        pendingHolderRoleId = null;
        pendingHolderName = null;
        pendingGroup = null;
        clientRequestDone = false;
        pendingClientReply = null;
        notifyAll();
    }

    // ── Web 挂载心跳 ─────────────────────────────────────────

    /** 标记 Web 前端挂载 (任意 API 轮询即刷新心跳). */
    public void markAttached() {
        lastAttachMillis = System.currentTimeMillis();
    }

    /** 是否已有 Web 前端挂载 (TTL 内有过心跳). */
    public boolean isAttached() {
        return System.currentTimeMillis() - lastAttachMillis < ATTACH_TTL_MS;
    }
}
