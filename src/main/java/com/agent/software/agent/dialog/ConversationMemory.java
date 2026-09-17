package com.agent.software.agent.dialog;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Text;
import com.agent.software.llm.LlmClient;
import com.agent.software.llm.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色 ↔ LLM 的当日对话记忆。
 *
 * <p>职责：跨任务连续性（历史 + 新任务）、超预算压缩、下班关闭当日上下文、随快照持久化。
 * 对应 master 的 {@code Conversation} + {@code ConversationManager}；但不再有进程级
 * 默认 manager，每个角色（{@code agent.Agent}）各持一份。
 */
public final class ConversationMemory {

    private static final Logger logger = LoggerFactory.getLogger(ConversationMemory.class);

    /** 压缩时摘要提示词（对齐 master {@code LLM.summarize} 的用法）。 */
    private static final String SUMMARY_SYSTEM =
            "You are a conversation-compaction assistant. Compress the dialogue below into one compact "
                    + "Chinese memo that keeps: the user's goals, the conclusions reached, the important "
                    + "facts/IDs/paths, and any unfinished work. Drop pleasantries and repetition.";

    private final RoleId agent;
    private final ConversationPolicy policy;

    private final List<Message> history = new ArrayList<>();
    private long totalChars;
    private int day;
    private int closedDay = -1;

    public ConversationMemory(RoleId agent, ConversationPolicy policy) {
        this.agent = agent;
        this.policy = policy == null ? ConversationPolicy.defaults() : policy;
    }

    /** 组装本次任务请求：[system prompt, ...历史, 当前任务]。 */
    public synchronized List<Message> prepare(String systemPrompt, String taskDescription, int day) {
        syncDay(day);
        if (closedDay == this.day) {
            // 当天已下班关闭，又来了新任务（例如 EMERGENCY）→ 冲刷旧上下文重开
            if (!history.isEmpty()) {
                logger.debug("[{}] 当日对话已关闭，重开并丢弃 {} 条历史", agent.value(), history.size());
            }
            history.clear();
            totalChars = 0;
            closedDay = -1;
        }
        List<Message> messages = new ArrayList<>(history.size() + 2);
        if (!Text.isBlank(systemPrompt)) {
            messages.add(Message.system(systemPrompt));
        }
        messages.addAll(history);
        messages.add(Message.user(Text.orEmpty(taskDescription)));
        return messages;
    }

    /** 任务完成后提交这一轮交换；超预算则触发压缩。 */
    public synchronized void commit(int day, String userText, String assistantText, LlmClient llm) {
        syncDay(day);
        if (closedDay == this.day) {
            logger.debug("[{}] 当日对话已关闭，不提交收尾交换", agent.value());
            return;
        }
        String u = Text.orEmpty(userText);
        String a = Text.orEmpty(assistantText);
        if (u.isEmpty() && a.isEmpty()) {
            return;
        }
        if (!u.isEmpty()) {
            add(Message.user(u));
        }
        if (!a.isEmpty()) {
            add(Message.assistant(a, List.of()));
        }
        if (policy.shouldCompact(totalChars)) {
            compact(llm);
        }
    }

    /** 下班关闭当日对话（上下文冲刷）。 */
    public synchronized void closeDay(int day) {
        int d = day <= 0 ? 1 : day;
        int dropped = history.size();
        history.clear();
        totalChars = 0;
        this.day = d;
        this.closedDay = d;
        if (dropped > 0) {
            logger.info("[{}] 第 {} 天对话已关闭（丢弃 {} 条消息）", agent.value(), d, dropped);
        }
    }

    public synchronized boolean isEmpty() {
        return history.isEmpty();
    }

    public synchronized int historySize() {
        return history.size();
    }

    public synchronized State snapshot() {
        return new State(day, closedDay, new ArrayList<>(history));
    }

    public synchronized void restore(State state) {
        history.clear();
        totalChars = 0;
        if (state == null) {
            return;
        }
        day = state.day();
        closedDay = state.closedDay();
        if (state.messages() != null) {
            for (Message m : state.messages()) {
                if (m != null) {
                    add(m);
                }
            }
        }
    }

    // ── 内部 ───────────────────────────────────────────────────

    private void syncDay(int currentDay) {
        int d = currentDay <= 0 ? 1 : currentDay;
        if (day != d) {
            if (!history.isEmpty()) {
                logger.debug("[{}] 对话跨天 {} → {}，丢弃 {} 条旧消息", agent.value(), day, d, history.size());
            }
            history.clear();
            totalChars = 0;
            day = d;
            closedDay = -1;
        }
    }

    private void add(Message message) {
        history.add(message);
        totalChars += Text.orEmpty(message.content()).length();
    }

    /**
     * 超预算压缩：先请 LLM 把整段历史摘要成一条 user 消息；失败（无 LLM / 报错 / 空摘要）
     * 就退化为"丢弃最旧消息"，保证上下文一定变小。
     */
    private void compact(LlmClient llm) {
        if (history.size() <= 1) {
            return;
        }
        long before = totalChars;
        int count = history.size();
        String summary = null;
        if (llm != null) {
            try {
                LlmClient.ChatReply reply = llm.summarize(dump(), 0.3, 800);
                if (reply != null && !reply.failed()) {
                    summary = Text.orEmpty(reply.text()).strip();
                }
            } catch (RuntimeException e) {
                logger.warn("[{}] 对话压缩失败：{}", agent.value(), e.getMessage());
            }
        }
        if (summary != null && !summary.isEmpty()) {
            history.clear();
            totalChars = 0;
            String compacted = "[Earlier dialogue summary (compacted from " + count + " messages)]\n"
                    + Text.truncate(summary, policy.maxSummaryChars());
            add(Message.user(compacted));
            logger.info("[{}] 对话已压缩：{} → {} 字符（{} 条合并为一条摘要）",
                    agent.value(), before, totalChars, count);
            return;
        }
        while (totalChars > policy.maxHistoryChars() && history.size() > policy.keepMessages()) {
            Message removed = history.remove(0);
            totalChars -= Text.orEmpty(removed.content()).length();
        }
        // 兜底：仍然超预算就从最新往回硬截断
        for (int i = history.size() - 1; i >= 0 && totalChars > policy.maxHistoryChars(); i--) {
            Message m = history.get(i);
            String c = Text.orEmpty(m.content());
            long need = totalChars - policy.maxHistoryChars();
            if (need >= c.length()) {
                history.remove(i);
                totalChars -= c.length();
            } else {
                history.set(i, new Message(m.role(), c.substring(0, (int) (c.length() - need)),
                        m.toolCalls(), m.toolCallId()));
                totalChars -= need;
            }
        }
        logger.warn("[{}] 对话压缩退化为丢弃最旧消息，剩余 {} 字符 / {} 条",
                agent.value(), totalChars, history.size());
    }

    private String dump() {
        StringBuilder sb = new StringBuilder();
        for (Message m : history) {
            sb.append(m.role().name().toLowerCase(java.util.Locale.ROOT)).append("> ")
                    .append(Text.orEmpty(m.content())).append('\n');
        }
        return sb.toString();
    }

    /** 便于提示词侧/测试观察的摘要提示词。 */
    public static String summarySystemPrompt() {
        return SUMMARY_SYSTEM;
    }

    /** 持久化形状。 */
    public record State(int day, int closedDay, List<Message> messages) {
    }
}
