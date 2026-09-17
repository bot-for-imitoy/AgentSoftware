package com.agent.software.transcript;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.agent.software.transcript.Transcript.Client;
import com.agent.software.transcript.Transcript.Entry;
import com.agent.software.transcript.Transcript.Feed;
import com.agent.software.transcript.Transcript.Talk;
import com.agent.software.transcript.Transcript.TraceMeta;

/**
 * 内存环形缓冲的轨迹流 + 客户回复会合点：Web 端按 watermark 增量拉取，客户输入阻塞式等待。
 *
 * <p>线程模型：写入与读取共用一把 {@link ReentrantLock}，{@link #since(long)} 返回拷贝，
 * 内部缓冲不外泄；客户回复用同一把锁上的 {@link Condition} 会合，等待可被中断。
 *
 * <p>kind 取值必须与前端 {@code app.js} 的实际分支一致（{@code reason}/{@code note}/
 * {@code tool}/{@code answer}/{@code talk}/{@code client}）——前端不认识的 kind 会被当成
 * 普通聊天气泡渲染，轨迹卡片就丢了。注意 master 的常量名是 {@code KIND_REASON}/
 * {@code KIND_TOOL}，字符串即 {@code "reason"}/{@code "tool"}（不是 {@code "reasoning"}/
 * {@code "tool_call"}）。
 */
public final class ChatFeed implements Transcript.Feed {

    /** 历史容量上限（对齐 master 的 {@code ChatStore.MAX_HISTORY}=5000，超出丢最旧）。 */
    public static final int MAX_HISTORY = 5000;

    /** 轨迹 kind：链式思考（LLM reasoning_content）。 */
    public static final String KIND_REASON = "reason";
    /** 轨迹 kind：工具调用中间叙述。 */
    public static final String KIND_NOTE = "note";
    /** 轨迹 kind：单次工具调用（工具名/参数/结果在 extra）。 */
    public static final String KIND_TOOL = "tool";
    /** 轨迹 kind：任务最终产出。 */
    public static final String KIND_ANSWER = "answer";
    /** 轨迹 kind：组内对话。 */
    public static final String KIND_TALK = "talk";
    /** 轨迹 kind：与客户的往来（组长提问 + 客户回复）。 */
    public static final String KIND_CLIENT = "client";
    /** 轨迹 kind：系统通知。 */
    public static final String KIND_SYSTEM = "system";

    /** 客户在聊天记录里的显示名（前端据此把气泡标成"来自客户"）。 */
    public static final String CLIENT_NAME = "Client A";

    /** 浏览器在线心跳 TTL：最近一次 attach/轮询在此窗口内即视为在线。 */
    public static final long ATTACH_TTL_MS = 15_000L;

    /** 角色 → 展示名/组 的解析器；engine 侧可选注入，缺省退化为 roleId。 */
    @FunctionalInterface
    public interface RoleResolver {
        /** 找不到返回 null。 */
        RoleInfo resolve(RoleId id);
    }

    /** 轨迹展示所需的角色信息（名称 + 所属组）。 */
    public record RoleInfo(String name, String group) {
    }

    private final ArrayDeque<Entry> history = new ArrayDeque<>();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition replyArrived = lock.newCondition();

    /** 下一个待分配的 seq（也是当前 watermark）。 */
    private long lastSeq;

    /** 角色展示信息解析器（volatile：允许装配完成后注入）。 */
    private volatile RoleResolver resolver;

    // ── 浏览器心跳 ───────────────────────────────────────────────
    private long lastAttachMillis;

    // ── 客户对话状态（受 lock 保护） ─────────────────────────────
    /** 是否正有一个成员阻塞在 {@link #awaitClientReply(Duration)}。 */
    private boolean waiting;
    /** 最近一次 {@link #client(Client)} 写入的提问方身份。 */
    private String lastClientRoleId = "";
    private String lastClientName = "";
    private String lastClientGroup = "";
    /** 最近一次客户提问文本。 */
    private String pendingQuestion = "";
    /** 已投递但尚无等待者取走的客户回复（下一次 await 立即取走）。 */
    private String pendingReply;

    /** 无角色解析器的默认构造（轨迹条目只有 roleId，没有显示名/组）。 */
    public ChatFeed() {
        this(null);
    }

    /** 注入角色解析器的构造：轨迹写入时据此填 fromName/group。 */
    public ChatFeed(RoleResolver resolver) {
        this.resolver = resolver;
    }

    /** 装配完成后补注入解析器（web 端拿不到 Team 时可用 CompanyView.roster 兜底绑定）。 */
    public void bindResolver(RoleResolver resolver) {
        this.resolver = resolver;
    }

    // ── 轨迹写入 ────────────────────────────────────────────────

    @Override
    public void reasoning(RoleId agent, String text, TraceMeta meta) {
        RoleInfo who = who(agent);
        append(KIND_REASON, who.group(), idOf(agent), who.name(), "", "", text, traceExtra(meta));
    }

    @Override
    public void note(RoleId agent, String text, TraceMeta meta) {
        RoleInfo who = who(agent);
        append(KIND_NOTE, who.group(), idOf(agent), who.name(), "", "", text, traceExtra(meta));
    }

    @Override
    public void toolCall(RoleId agent, String toolName, String argsJson, String result, TraceMeta meta) {
        RoleInfo who = who(agent);
        // text 故意留空：前端只读 extra.tool/args/result 渲染工具卡片
        Payload extra = traceExtra(meta)
                .with("tool", Text.orEmpty(toolName))
                .with("args", Text.orEmpty(argsJson))
                .with("result", result);
        append(KIND_TOOL, who.group(), idOf(agent), who.name(), "", "", "", extra);
    }

    @Override
    public void answer(RoleId agent, String text, boolean failed, int tokens, TraceMeta meta) {
        RoleInfo who = who(agent);
        Payload extra = traceExtra(meta)
                .with("status", failed ? "failed" : "done")
                .with("tokens", tokens);
        append(KIND_ANSWER, who.group(), idOf(agent), who.name(), "", "", text, extra);
    }

    @Override
    public void talk(Talk record) {
        if (record == null) {
            return;
        }
        // Entry 没有 urgency 字段：放进 extra，ChatWebServer 序列化时再提到顶层
        Payload extra = Text.isBlank(record.urgency())
                ? Payload.empty()
                : Payload.of("urgency", record.urgency());
        append(KIND_TALK, record.group(), idOf(record.from()), Text.orEmpty(record.fromName()),
                idOf(record.to()), Text.orEmpty(record.toName()), record.text(), extra);
    }

    @Override
    public void client(Client record) {
        if (record == null) {
            return;
        }
        String roleId = idOf(record.role());
        lock.lock();
        try {
            lastClientRoleId = roleId;
            lastClientName = Text.orEmpty(record.name());
            lastClientGroup = Text.orEmpty(record.group());
            pendingQuestion = Text.orEmpty(record.text());
        } finally {
            lock.unlock();
        }
        // 组长 → 客户的问题（fromName 是组长，toName 是 Client A）
        append(KIND_CLIENT, record.group(), roleId, Text.orEmpty(record.name()),
                "", CLIENT_NAME, record.text(), Payload.empty());
    }

    @Override
    public void system(String text) {
        append(KIND_SYSTEM, "", "", "System", "", "", text, Payload.empty());
    }

    @Override
    public long watermark() {
        lock.lock();
        try {
            return lastSeq;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Entry> since(long seq) {
        lock.lock();
        try {
            List<Entry> out = new ArrayList<>();
            for (Entry e : history) {
                if (e.seq() > seq) {
                    out.add(e);
                }
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    // ── 客户回复会合点（WebClientChannel ⇄ ChatWebServer 的握手） ──────────

    /**
     * 是否有浏览器在线（收到过 /api/attach 或轮询心跳且未超时）。
     *
     * <p>{@code tool.client.WebClientChannel#interactive()} 直接问它；没有浏览器时
     * Web 通道视为离线，组长会拿到"客户暂时联系不上"。
     */
    public boolean clientAttached() {
        lock.lock();
        try {
            return attachedLocked();
        } finally {
            lock.unlock();
        }
    }

    /** 刷新浏览器在线心跳（/api/attach 与任何 API 轮询都会调用）。 */
    public void touchAttach() {
        lock.lock();
        try {
            lastAttachMillis = System.currentTimeMillis();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 阻塞等待浏览器提交的客户回复。
     *
     * @param timeout null 表示一直等
     * @return 客户回复文本；超时或通道关闭返回空
     */
    public Optional<String> awaitClientReply(Duration timeout) {
        lock.lock();
        try {
            // 已经存下的回复（提交时无人等待）立即取走，不再阻塞
            if (pendingReply != null) {
                String text = pendingReply;
                pendingReply = null;
                return Optional.of(text);
            }
            waiting = true;
            try {
                if (timeout == null) {
                    while (pendingReply == null) {
                        replyArrived.await();
                    }
                } else {
                    long remain = timeout.toNanos();
                    while (pendingReply == null) {
                        if (remain <= 0L) {
                            return Optional.empty();
                        }
                        remain = replyArrived.awaitNanos(remain);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } finally {
                waiting = false;
            }
            String text = pendingReply;
            pendingReply = null;
            return Optional.of(text);
        } finally {
            lock.unlock();
        }
    }

    /** 浏览器提交客户回复（/api/reply）；返回 true 表示确实有等待者被唤醒。 */
    public boolean submitClientReply(String text) {
        String value = Text.orEmpty(text);
        lock.lock();
        try {
            boolean delivered = waiting;
            // 无人等待时留作 pending，下一次 await 立即取走
            pendingReply = value;
            if (delivered) {
                replyArrived.signalAll();
            }
            // 客户回复也要进轨迹：前端 /api/reply 的 message 字段直接渲染这条气泡
            append(KIND_CLIENT, lastClientGroup, "", CLIENT_NAME,
                    delivered ? lastClientRoleId : "", delivered ? lastClientName : "",
                    value, Payload.empty());
            return delivered;
        } finally {
            lock.unlock();
        }
    }

    /** 最近的客户往来状态（供 /api/state 展示）。 */
    public ClientDialogue clientDialogue() {
        lock.lock();
        try {
            boolean w = waiting;
            return new ClientDialogue(attachedLocked(),
                    w ? lastClientRoleId : null,
                    w ? lastClientName : null,
                    Text.orEmpty(pendingQuestion));
        } finally {
            lock.unlock();
        }
    }

    /** 客户对话的对外状态快照。 */
    public record ClientDialogue(boolean attached, String waitingRoleId, String waitingName,
                                 String pendingQuestion) {
    }

    // ── 内部实现 ────────────────────────────────────────────────

    /** 追加一条轨迹（分配 seq、入环形缓冲）。调用方需自行处理 null 校验。 */
    private Entry append(String kind, String group, String fromRoleId, String fromName,
                         String toRoleId, String toName, String text, Payload extra) {
        long ts = System.currentTimeMillis();
        lock.lock();
        try {
            long seq = ++lastSeq;
            Entry entry = new Entry(seq, ts, Text.orEmpty(kind), Text.orEmpty(group),
                    Text.orEmpty(fromRoleId), Text.orEmpty(fromName),
                    Text.orEmpty(toRoleId), Text.orEmpty(toName),
                    Text.orEmpty(text), extra == null ? Payload.empty() : extra);
            history.addLast(entry);
            while (history.size() > MAX_HISTORY) {
                history.removeFirst();
            }
            return entry;
        } finally {
            lock.unlock();
        }
    }

    /** 解析角色的展示名/组；没有解析器时退化为 roleId（组为空）。 */
    private RoleInfo who(RoleId id) {
        RoleResolver r = resolver;
        if (r != null && id != null) {
            RoleInfo info = r.resolve(id);
            if (info != null) {
                return new RoleInfo(Text.orEmpty(info.name()), Text.orEmpty(info.group()));
            }
        }
        return new RoleInfo(idOf(id), "");
    }

    private static String idOf(RoleId id) {
        return id == null ? "" : id.value();
    }

    /** TraceMeta → extra（taskId/round）；null 值由 Payload 自动丢弃。 */
    private static Payload traceExtra(TraceMeta meta) {
        Payload p = Payload.empty();
        if (meta == null) {
            return p;
        }
        if (meta.taskId() != null) {
            p = p.with("taskId", meta.taskId().value());
        }
        if (meta.round() != null) {
            p = p.with("round", meta.round());
        }
        return p;
    }

    private boolean attachedLocked() {
        return lastAttachMillis > 0L
                && System.currentTimeMillis() - lastAttachMillis < ATTACH_TTL_MS;
    }
}
