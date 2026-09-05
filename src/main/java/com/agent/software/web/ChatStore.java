package com.agent.software.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat message store + client dialogue coordination (data source for the Web UI).
 *
 * <p>Each {@link com.agent.software.AgentSystem} holds its own ChatStore instance,
 * recording the following message kinds (the Web frontend renders each kind differently):
 * <ul>
 *   <li>{@code talk}  — in-group talk messages between roles (recorded after TalkTo delivers);</li>
 *   <li>{@code client} — dialogue between leadership members and the client (user) (talk_to_client);</li>
 *   <li>{@code reason} — a role's chain of thought (LLM {@code reasoning_content}, recorded per tool-loop round);</li>
 *   <li>{@code note} — assistant narration produced in the middle of a tool-calling round (content accompanying tool calls);</li>
 *   <li>{@code tool} — one tool invocation: the tool name, parsed arguments and the tool result (structured in {@code extra});</li>
 *   <li>{@code answer} — the task's final output (the final LLM reply / task result).</li>
 * </ul>
 * Messages carry a monotonically increasing {@code seq}; the Web frontend pulls
 * incrementally via {@code since}.
 *
 * <p>Client dialogue coordination: the Web-page input channel
 * ({@link com.agent.software.io.WebInput}, used by talk_to_client on Web-configured systems) calls
 * {@link #beginClientWait} to set the "waiting for client reply" state (the Web frontend enables
 * the input box based on this), then blocks on {@link #awaitClientReply} until a reply submitted via
 * {@link #postClientReply} arrives (returns null on timeout). Console-based systems
 * ({@link com.agent.software.io.StdInput}) read {@code System.in} directly and never touch this state.
 */
public final class ChatStore {

    /** History cap (oldest messages are dropped beyond this, to avoid unbounded memory growth). */
    public static final int MAX_HISTORY = 5000;

    /** Message kind: in-group talk between roles. */
    public static final String KIND_TALK = "talk";
    /** Message kind: dialogue with the client (user). */
    public static final String KIND_CLIENT = "client";
    /** Message kind: chain of thought (LLM reasoning_content). */
    public static final String KIND_REASON = "reason";
    /** Message kind: assistant narration inside a tool-calling round (content accompanying tool calls). */
    public static final String KIND_NOTE = "note";
    /** Message kind: a single tool invocation (tool name / arguments / result in {@code extra}). */
    public static final String KIND_TOOL = "tool";
    /** Message kind: the task's final output (final LLM reply / task result). */
    public static final String KIND_ANSWER = "answer";

    /** Display name of the client in the chat log. */
    public static final String CLIENT_NAME = "Client A";

    /** A single chat message (all fields public, for easy serialization to a JSON Map). */
    public static final class ChatMessage {
        public long seq;
        public long ts;            // epoch millis
        public String kind;        // talk / client / reason / note / tool / answer
        public String group;       // group (English name), the frontend filters by group
        public String fromRoleId;  // sender role_id (empty string for the client)
        public String fromName;    // sender name
        public String toRoleId;    // recipient role_id
        public String toName;      // recipient name
        public String text;        // message content (for tool messages: empty; structured fields live in extra)
        public String urgency;     // urgency of the talk message (nullable)
        /** Optional structured payload (tool/args/result/round/status/tokens…); serialized as a JSON object. */
        public Map<String, Object> extra = new LinkedHashMap<>();
    }

    private final List<ChatMessage> history = new ArrayList<>();
    private long lastSeq = 0;

    // ── Client dialogue coordination state (synchronized on this) ──
    private boolean pendingClientRequest = false;   // whether we are waiting for the client reply
    private String pendingHolderRoleId = null;
    private String pendingHolderName = null;
    private String pendingGroup = null;             // group of the member who started the dialogue
    private boolean clientRequestDone = false;
    private String pendingClientReply = null;

    // ── Web attach heartbeat (volatile, lock-free) ───────────────
    private volatile long lastAttachMillis = 0L;
    /** Heartbeat TTL: the Web frontend is considered attached if it polled within this window. */
    public static final long ATTACH_TTL_MS = 60_000L;

    // ── Message recording ────────────────────────────────────────

    /**
     * Records a chat message. Thread-safe.
     *
     * @return the recorded message object (with the allocated seq).
     */
    public ChatMessage record(String kind, String group, String fromRoleId, String fromName,
                              String toRoleId, String toName, String text, String urgency) {
        return record(kind, group, fromRoleId, fromName, toRoleId, toName, text, urgency, null);
    }

    /**
     * Records a chat message with an optional structured payload (tool name / arguments / result,
     * task id, round number, status, token usage…). Thread-safe.
     *
     * @return the recorded message object (with the allocated seq).
     */
    public ChatMessage record(String kind, String group, String fromRoleId, String fromName,
                              String toRoleId, String toName, String text, String urgency,
                              Map<String, Object> extra) {
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
        if (extra != null && !extra.isEmpty()) {
            m.extra.putAll(extra);
        }
        synchronized (history) {
            m.seq = ++lastSeq;
            history.add(m);
            while (history.size() > MAX_HISTORY) {
                history.remove(0);
            }
        }
        return m;
    }

    /** Current max seq (frontend incremental pull watermark). */
    public long lastSeq() {
        synchronized (history) {
            return lastSeq;
        }
    }

    /** Returns all messages with seq &gt; sinceSeq (ascending by seq), serialized as a list of JSON Maps. */
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

    /** Message → JSON Map. */
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
        if (m.extra != null && !m.extra.isEmpty()) {
            out.put("extra", new LinkedHashMap<>(m.extra));
        }
        return out;
    }

    // ── Client dialogue coordination ─────────────────────────────

    /**
     * Registers "waiting for the client reply" (Web mode: called by {@code WebInput.read}).
     *
     * @return null = success; otherwise an error description (another member is already waiting).
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

    /** Whether we are currently waiting for the client reply (one of the conditions enabling the Web input box). */
    public synchronized boolean isClientWaitPending() {
        return pendingClientRequest;
    }

    /** Name of the waiting member (null when none). */
    public synchronized String pendingHolderName() {
        return pendingHolderName;
    }

    /** role_id of the waiting member (null when none). */
    public synchronized String pendingHolderRoleId() {
        return pendingHolderRoleId;
    }

    /** Group of the waiting member (null when none). */
    public synchronized String pendingGroup() {
        return pendingGroup;
    }

    /**
     * The client submits a reply (Web frontend POST /api/reply). Records the client message and
     * wakes the waiting member.
     *
     * @return the recorded message; null when there is no pending client dialogue.
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
     * Blocks waiting for the client reply (Web mode, called by {@code WebInput.read}).
     *
     * @param timeoutMs maximum wait in milliseconds.
     * @return the client reply text; null on timeout or interruption.
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

    /** Ends the waiting state (reply received or timed out), called by {@code WebInput.read}. */
    public synchronized void endClientWait() {
        pendingClientRequest = false;
        pendingHolderRoleId = null;
        pendingHolderName = null;
        pendingGroup = null;
        clientRequestDone = false;
        pendingClientReply = null;
        notifyAll();
    }

    // ── Web attach heartbeat ─────────────────────────────────────

    /** Marks the Web frontend as attached (any API poll refreshes the heartbeat). */
    public void markAttached() {
        lastAttachMillis = System.currentTimeMillis();
    }

    /** Whether a Web frontend is attached (a heartbeat within the TTL window). */
    public boolean isAttached() {
        return System.currentTimeMillis() - lastAttachMillis < ATTACH_TTL_MS;
    }
}
