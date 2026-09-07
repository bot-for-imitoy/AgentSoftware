package com.agent.software.conversation;

import com.agent.software.llm.LLM;
import com.agent.software.utils.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conversation (role ↔ LLM API conversation state) — one ongoing dialogue per role.
 *
 * <p>Before this class, each task run built its messages from scratch (system prompt + task
 * description) and threw them away when the task ended, so a role had no conversational continuity
 * with the LLM API within a day. {@code Conversation} sits exactly at that seam — between the role
 * ({@link com.agent.software.role.AgentRole}) and the LLM API ({@link com.agent.software.llm.LLM}):
 *
 * <ul>
 *   <li><b>Cross-task continuity</b> — when a task completes, its exchange (the user task text +
 *       the model's final answer) is committed to the conversation; the next task's request is
 *       prepared from {@code system prompt + committed history + new task}.</li>
 *   <li><b>Automatic context compaction</b> — once the committed history exceeds the character
 *       budget ({@link #DEFAULT_MAX_HISTORY_CHARS}), the whole history is summarized into one
 *       compact message with {@link LLM#summarize}; if the summary call fails, the oldest
 *       messages are dropped instead, so a runaway context can never be sent to the API.</li>
 *   <li><b>Shift lifecycle</b> — the conversation is keyed by work day. When the role writes its
 *       end-of-day summary and goes {@code OFF_DUTY}, {@link #closeDay(int)} clears the dialogue
 *       (the day's recap has been persisted by the {@code summary} tool); a new day (or a
 *       same-day task after off-duty, i.e. context-flush semantics) starts a fresh conversation.</li>
 *   <li><b>Persistence</b> — {@link #toDict()} / {@link #restore(Map)} serialize the open
 *       dialogue into the state archive ({@link com.agent.software.store.StateStore}), so a run
 *       interrupted mid-day resumes exactly where the role left off.</li>
 * </ul>
 *
 * <p>Thread-safety: the conversation is normally only touched by the role's own worker thread,
 * but the StateStore archive thread may snapshot it concurrently, so state-mutating methods are
 * synchronized. History messages are stored as {@code {role: "user"|"assistant", content: "..."}}
 * maps — the same shape the tool-calling loop sends to OpenAI-compatible endpoints.
 */
public final class Conversation {

    private static final Logger logger = LoggerFactory.getLogger(Conversation.class);

    /** Default character budget for committed history before automatic compaction kicks in. */
    public static final int DEFAULT_MAX_HISTORY_CHARS = 24_000;

    /** Upper bound of a compaction summary message (chars). */
    public static final int MAX_COMPACT_SUMMARY_CHARS = 12_000;

    /** Cap on assistant tool-recap text kept in the conversation (chars, per tool call). */
    public static final int TOOL_RECAP_RESULT_MAX = 200;

    /** Cap on tool arguments shown in a recap (chars). */
    public static final int TOOL_RECAP_ARGS_MAX = 80;

    /** Max tool calls recapped per task (further calls are folded into a trailing marker). */
    public static final int TOOL_RECAP_LIMIT = 6;

    private final String roleId;
    private final int maxHistoryChars;

    private int day = 0;                // work day this conversation belongs to (0 = never opened)
    private int closedDay = -1;         // day whose dialogue was closed at shift end (-1 = open)

    /** Committed dialogue: alternating user/assistant message maps, oldest first. */
    private final List<Map<String, Object>> history = new ArrayList<>();
    private long totalChars = 0;        // cached sum of message content lengths

    public Conversation(String roleId) {
        this(roleId, DEFAULT_MAX_HISTORY_CHARS);
    }

    public Conversation(String roleId, int maxHistoryChars) {
        this.roleId = roleId != null ? roleId : "";
        this.maxHistoryChars = maxHistoryChars > 0 ? maxHistoryChars : DEFAULT_MAX_HISTORY_CHARS;
    }

    // ── Introspection ─────────────────────────────────────────

    public String roleId() {
        return roleId;
    }

    public synchronized int day() {
        return day;
    }

    public synchronized int closedDay() {
        return closedDay;
    }

    /** Whether the conversation holds no committed messages. */
    public synchronized boolean isEmpty() {
        return history.isEmpty();
    }

    public synchronized int historySize() {
        return history.size();
    }

    /** Committed history total content length in characters. */
    public synchronized long totalChars() {
        return totalChars;
    }

    /** The character budget that triggers automatic compaction for this conversation. */
    public synchronized int maxHistoryChars() {
        return maxHistoryChars;
    }

    /** Copy of the committed history (role/content maps, oldest first) — for stats/tests. */
    public synchronized List<Map<String, Object>> historySnapshot() {
        List<Map<String, Object>> out = new ArrayList<>(history.size());
        for (Map<String, Object> m : history) {
            out.add(copyMsg(m));
        }
        return out;
    }

    /** Rough token estimate of the committed history (chars / 3), for logging. */
    public synchronized long estimatedTokens() {
        return totalChars / 3;
    }

    /** Human-readable stats line for logs/debug. */
    public synchronized String stats() {
        return roleId + ": day " + day + ", " + history.size() + " messages, ~"
                + totalChars + " chars (~" + estimatedTokens() + " tokens)"
                + (closedDay == day ? ", closed" : "");
    }

    // ── Day bookkeeping ───────────────────────────────────────

    /**
     * Align the conversation with the current work day: a different day than the stored one
     * (usually a new shift start) drops the previous day's dialogue — its recap was already
     * persisted by the role into that day's summary — and starts fresh. Reopening a closed
     * (off-duty) conversation on the same day is handled by {@link #prepareMessages} only, which
     * is the point where a genuinely new task begins; {@link #appendTaskExchange} must never
     * reopen a closed day (it is what commits the trailing "summary saved" exchange).
     */
    private void syncDay(int currentDay) {
        if (currentDay <= 0) {
            currentDay = 1;
        }
        if (day != currentDay) {
            if (!history.isEmpty()) {
                logger.debug("[{}] conversation day {} → {}: dropping {} stale message(s)",
                        roleId, day, currentDay, history.size());
            }
            clearHistory();
            day = currentDay;
            closedDay = -1;
        }
    }

    private void clearHistory() {
        history.clear();
        totalChars = 0;
    }

    // ── Task-run preparation & commit ──────────────────────────

    /**
     * Build the full message list for one task run:
     * {@code [system prompt, ...committed history (copies), user task]}.
     * Called by the role worker at the start of every task.
     *
     * <p>If the day was closed by the end-of-day summary and a new task still arrives on the same
     * day (e.g. an EMERGENCY event), preparing reopens the conversation with a clean context —
     * the "context flushed when off duty" semantics.
     */
    public synchronized List<Map<String, Object>> prepareMessages(String systemPrompt,
                                                                  String taskDescription,
                                                                  int currentDay) {
        syncDay(currentDay);
        if (closedDay == currentDay) {
            // same day, was closed (off duty) → reopening flushes the previous context
            if (!history.isEmpty()) {
                logger.debug("[{}] conversation reopened on day {} (was closed): dropping {} message(s)",
                        roleId, day, history.size());
            }
            clearHistory();
            closedDay = -1;
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(msg("system", systemPrompt));
        }
        for (Map<String, Object> m : history) {
            messages.add(copyMsg(m));
        }
        messages.add(msg("user", taskDescription == null ? "" : taskDescription));
        return messages;
    }

    /**
     * Commit one completed task exchange (user task text + model final answer) to the dialogue.
     *
     * @return {@code false} when nothing was committed: both texts are empty, or the day has
     *         already been closed by the end-of-day summary (the trailing "summary saved"
     *         exchange must not linger in the cleared conversation).
     */
    public synchronized boolean appendTaskExchange(int currentDay, String userText,
                                                   String assistantText, LLM llm) {
        syncDay(currentDay);
        if (closedDay == currentDay) {
            logger.debug("[{}] conversation closed for day {}, skip appending the trailing exchange", roleId, day);
            return false;
        }
        String u = userText == null ? "" : userText;
        String a = assistantText == null ? "" : assistantText;
        if (u.isEmpty() && a.isEmpty()) {
            return false;
        }
        if (!u.isEmpty()) {
            addMessage("user", u);
        }
        if (!a.isEmpty()) {
            addMessage("assistant", a);
        }
        if (totalChars > maxHistoryChars) {
            compact(llm);
        }
        return true;
    }

    private void addMessage(String role, String content) {
        history.add(msg(role, content));
        totalChars += content.length();
    }

    // ── Context compaction ─────────────────────────────────────

    /**
     * Compress the committed history when it exceeds the character budget: summarize the whole
     * history into one compact user message via the LLM (an extra API call, but only once per
     * budget crossing). On failure (no LLM / API error / empty summary) the oldest messages are
     * dropped, so the context always shrinks.
     */
    private synchronized void compact(LLM llm) {
        if (history.size() <= 1) {
            return;
        }
        long before = totalChars;
        int msgCount = history.size();
        String dump = dumpHistory();
        String summary = null;
        if (llm != null) {
            try {
                LLM.ChatResponse resp = llm.summarize(dump, 0.3, 800);
                String text = resp == null || resp.text == null ? "" : resp.text.strip();
                if (!text.isEmpty() && !text.startsWith(LLM.LLM_ERROR_MARKERS)) {
                    summary = text;
                }
            } catch (Exception e) {
                logger.warn("[{}] conversation compaction summarize failed: {}", roleId,
                        e.getMessage() == null ? e : e.getMessage());
            }
        }
        if (summary != null) {
            clearHistory();
            String compactContent = "[Earlier dialogue summary (compacted from " + msgCount + " messages)]\n"
                    + truncate(summary, MAX_COMPACT_SUMMARY_CHARS);
            history.add(msg("user", compactContent));
            totalChars += compactContent.length();
            logger.info("[{}] conversation compacted: {} chars → {} chars ({} messages merged into one summary)",
                    roleId, before, totalChars, msgCount);
        } else {
            // Fallback: drop oldest messages until within budget (keep at least the newest exchange)
            while (totalChars > maxHistoryChars && history.size() > 2) {
                Map<String, Object> first = history.remove(0);
                totalChars -= textOf(first).length();
            }
            truncateToBudget();
            logger.warn("[{}] conversation compaction fallback: dropped oldest messages, {} chars remain (~{} messages)",
                    roleId, totalChars, history.size());
        }
    }

    /** Emergency last resort: hard-truncate message tails (from the newest backwards) to fit the budget. */
    private void truncateToBudget() {
        if (history.isEmpty()) {
            return;
        }
        for (int i = history.size() - 1; i >= 0 && totalChars > maxHistoryChars; i--) {
            Map<String, Object> m = history.get(i);
            String c = textOf(m);
            long need = totalChars - maxHistoryChars;
            if (need >= c.length()) {
                history.remove(i);
                totalChars -= c.length();
            } else {
                int keep = c.length() - (int) need;
                m.put("content", c.substring(0, keep));
                totalChars -= need;
            }
        }
    }

    /** One-line dump of the history for summarization: {@code user> ... / assistant> ...}. */
    private String dumpHistory() {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : history) {
            sb.append(String.valueOf(m.get("role"))).append("> ").append(textOf(m)).append("\n");
        }
        return sb.toString();
    }

    // ── Shift-end lifecycle ────────────────────────────────────

    /**
     * Close the day's conversation. Called from the {@code summary} tool right after the daily
     * summary note is persisted and the role switches to {@code OFF_DUTY}: the dialogue is
     * cleared (its recap now lives in the day's summary file) and the day is marked closed so the
     * in-flight "summary saved" exchange is not appended afterwards.
     */
    public synchronized void closeDay(int currentDay) {
        if (currentDay <= 0) {
            currentDay = 1;
        }
        int dropped = history.size();
        long chars = totalChars;
        clearHistory();
        day = currentDay;
        closedDay = currentDay;
        if (dropped > 0) {
            logger.info("[{}] conversation closed for day {}: dropped {} messages (~{} chars)", roleId, day, dropped, chars);
        }
    }

    // ── Serialization (StateStore archive) ─────────────────────

    /** Conversation → Map (day, closed_day, messages). Only the open dialogue is meaningful. */
    public synchronized Map<String, Object> toDict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", day);
        m.put("closed_day", closedDay);
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (Map<String, Object> h : history) {
            msgs.add(copyMsg(h));
        }
        m.put("messages", msgs);
        return m;
    }

    /** Map → conversation state (from the StateStore archive). */
    public synchronized void restore(Map<String, Object> dict) {
        if (dict == null) {
            return;
        }
        clearHistory();
        day = Json.intVal(dict, "day", 0);
        Object cd = dict.get("closed_day");
        closedDay = cd instanceof Number n ? n.intValue() : -1;
        Object msgs = dict.get("messages");
        if (msgs instanceof List) {
            for (Object o : (List<?>) msgs) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mm = (Map<String, Object>) m;
                    String role = Json.str(mm, "role", "");
                    String content = Json.str(mm, "content", "");
                    if (!role.isEmpty()) {
                        addMessage(role, content);
                    }
                }
            }
        }
        logger.debug("[{}] conversation restored: day {}, {} messages", roleId, day, history.size());
    }

    // ── Message helpers ────────────────────────────────────────

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> copyMsg(Map<String, Object> m) {
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("role", String.valueOf(m.get("role")));
        copy.put("content", String.valueOf(m.get("content")));
        return copy;
    }

    private static String textOf(Map<String, Object> m) {
        Object c = m.get("content");
        return c == null ? "" : String.valueOf(c);
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }
}
