package com.agent.software.conversation;

import com.agent.software.llm.LLM;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the role ↔ LLM API conversation state (Conversation / ConversationManager):
 * cross-task continuity, day/off-duty lifecycle, automatic context compaction and serialization.
 */
class ConversationTest {

    /** Scriptable LLM whose summarize() returns a canned text (or a canned failure marker). */
    private static final class FakeLlm implements LLM {
        final String summarizeText;   // null → fail (API error marker)
        int summarizeCalls = 0;

        FakeLlm(String summarizeText) {
            this.summarizeText = summarizeText;
        }

        @Override
        public ToolsResponse chatWithTools(List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools,
                                           double temperature, Integer maxTokens) {
            return new ToolsResponse("", List.of(), null);
        }

        @Override
        public ChatResponse chat(String system, String user, double temperature, Integer maxTokens) {
            return new ChatResponse("", 0);
        }

        @Override
        public ChatResponse summarize(String logText, double temperature, Integer maxTokens) {
            summarizeCalls++;
            if (summarizeText == null) {
                return new ChatResponse("[API error: fake summarize failure]", 0);
            }
            return new ChatResponse(summarizeText, 0);
        }
    }

    // ── Cross-task continuity ─────────────────────────────────

    @Test
    void prepareMessagesStartsWithSystemAndTaskOnly() {
        Conversation c = new Conversation("ceo");
        List<Map<String, Object>> messages = c.prepareMessages("You are the CEO", "Task one", 1);
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).get("role"));
        assertEquals("You are the CEO", messages.get(0).get("content"));
        assertEquals("user", messages.get(1).get("role"));
        assertEquals("Task one", messages.get(1).get("content"));
        assertTrue(c.isEmpty());   // preparing does not commit anything
    }

    @Test
    void committedExchangeIsCarriedIntoNextTask() {
        Conversation c = new Conversation("ceo");
        List<Map<String, Object>> m1 = c.prepareMessages("sys", "Task one", 1);
        assertTrue(c.appendTaskExchange(1, "Task one", "Answer one", null));
        List<Map<String, Object>> m2 = c.prepareMessages("sys", "Task two", 1);
        // system + committed [user task1, assistant answer1] + new user task
        assertEquals(4, m2.size());
        assertEquals("user", m2.get(1).get("role"));
        assertEquals("Task one", m2.get(1).get("content"));
        assertEquals("assistant", m2.get(2).get("role"));
        assertEquals("Answer one", m2.get(2).get("content"));
        assertEquals("user", m2.get(3).get("role"));
        assertEquals("Task two", m2.get(3).get("content"));
        // the returned list must not share state with the conversation
        m1.clear();
        assertFalse(c.isEmpty());
    }

    @Test
    void emptyExchangeIsNotCommitted() {
        Conversation c = new Conversation("ceo");
        assertFalse(c.appendTaskExchange(1, "", "", null));
        assertTrue(c.isEmpty());
        assertEquals(1, c.day());   // day was opened by the first use
    }

    // ── Day / off-duty lifecycle ──────────────────────────────

    @Test
    void dayRolloverStartsFreshConversation() {
        Conversation c = new Conversation("ceo");
        c.appendTaskExchange(1, "Day one task", "Answer", null);
        assertEquals(2, c.historySize());
        List<Map<String, Object>> nextDay = c.prepareMessages("sys", "Day two task", 2);
        // old dialogue is dropped on the new day; [yesterday's summary] comes via the system prompt
        assertEquals(2, nextDay.size());
        assertEquals("system", nextDay.get(0).get("role"));
        assertEquals("user", nextDay.get(1).get("role"));
        assertEquals("Day two task", nextDay.get(1).get("content"));
        assertEquals(2, c.day());
        assertTrue(c.isEmpty());
    }

    @Test
    void closeDayClearsContextAndBlocksSameDayAppend() {
        Conversation c = new Conversation("ceo");
        c.appendTaskExchange(1, "Work", "Answer", null);
        c.closeDay(1);
        assertTrue(c.isEmpty());
        assertEquals(1, c.closedDay());
        // trailing "summary saved" exchange must not be appended on the closed day
        assertFalse(c.appendTaskExchange(1, "SHIFT_END summary", "Summary saved", null));
        assertTrue(c.isEmpty());
    }

    @Test
    void closedConversationReopensOnNextDay() {
        Conversation c = new Conversation("ceo");
        c.appendTaskExchange(1, "Work", "Answer", null);
        c.closeDay(1);
        // next day → open fresh
        assertTrue(c.appendTaskExchange(2, "New day task", "New answer", null));
        assertEquals(2, c.historySize());
        assertEquals(2, c.day());
        assertTrue(c.closedDay() < 0);
    }

    @Test
    void sameDayTaskAfterOffDutyFlushesContext() {
        Conversation c = new Conversation("ceo");
        c.appendTaskExchange(1, "Work", "Answer", null);
        c.closeDay(1);
        // an EMERGENCY task arrives the same day after off-duty: preparing reopens with a clean context
        List<Map<String, Object>> m = c.prepareMessages("sys", "Emergency task", 1);
        assertEquals(2, m.size());   // no stale dialogue
        assertTrue(c.isEmpty());
        assertTrue(c.closedDay() < 0);
    }

    // ── Automatic context compaction ──────────────────────────

    @Test
    void overBudgetHistoryIsSummarizedIntoOneMessage() {
        FakeLlm llm = new FakeLlm("MERGED: everything that happened earlier.");
        Conversation c = new Conversation("ceo", 120);   // tiny budget forces compaction
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            big.append("details-").append(i).append(' ');
        }
        assertTrue(c.appendTaskExchange(1, "Big task " + big, "Big answer " + big, llm));
        assertEquals(1, c.historySize());
        assertEquals(1, llm.summarizeCalls);
        Map<String, Object> only = c.toDict();
        List<?> msgs = (List<?>) only.get("messages");
        Map<?, ?> msg = (Map<?, ?>) msgs.get(0);
        assertEquals("user", msg.get("role"));
        assertTrue(((String) msg.get("content")).startsWith("[Earlier dialogue summary"));
        assertTrue(((String) msg.get("content")).contains("MERGED: everything that happened earlier."));
        // context stays within the budget
        assertTrue(c.totalChars() <= c.maxHistoryChars());
    }

    @Test
    void compactionFallsBackWhenSummarizeFails() {
        FakeLlm llm = new FakeLlm(null);   // summarize returns an API-error marker
        Conversation c = new Conversation("ceo", 80);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            big.append("details-").append(i).append(' ');
        }
        assertTrue(c.appendTaskExchange(1, "Big task " + big, "Big answer " + big, llm));
        assertEquals(1, llm.summarizeCalls);
        assertTrue(c.totalChars() <= 80, "context must shrink even when the summary call fails");
        // no synthetic summary message was inserted
        assertTrue(c.historySize() >= 1);
        for (Map<String, Object> msg : c.historySnapshot()) {
            assertFalse(((String) msg.get("content")).startsWith("[Earlier dialogue summary"));
        }
    }

    @Test
    void compactionFallsBackWithoutLlm() {
        Conversation c = new Conversation("ceo", 60);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            big.append("0123456789");
        }
        for (int t = 0; t < 4; t++) {
            c.appendTaskExchange(1, "Task " + t + " " + big, "Answer " + t, null);
        }
        assertTrue(c.totalChars() <= 60, "context must shrink even without a summarize call");
    }

    // ── Serialization ─────────────────────────────────────────

    @Test
    void toDictRestoreRoundTrip() {
        Conversation c = new Conversation("hr");
        c.appendTaskExchange(1, "Hire a developer", "Posted the job", null);
        c.appendTaskExchange(1, "Review candidates", "Shortlisted three", null);
        assertEquals(1, c.day());
        Map<String, Object> dict = c.toDict();
        assertEquals(4, ((List<?>) dict.get("messages")).size());
        assertEquals(1, ((Number) dict.get("day")).intValue());

        Conversation r = new Conversation("hr");
        r.restore(dict);
        assertEquals(4, r.historySize());
        assertEquals(1, r.day());
        Map<String, Object> restored = r.toDict();
        List<?> msgs = (List<?>) restored.get("messages");
        assertEquals("user", ((Map<?, ?>) msgs.get(2)).get("role"));
        assertEquals("Review candidates", ((Map<?, ?>) msgs.get(2)).get("content"));
        assertEquals("assistant", ((Map<?, ?>) msgs.get(3)).get("role"));
        assertEquals("Shortlisted three", ((Map<?, ?>) msgs.get(3)).get("content"));
    }

    @Test
    void restoreToleratesMissingFields() {
        Conversation c = new Conversation("ceo");
        c.restore(Map.of());   // no day / messages → stays empty, day 0
        assertTrue(c.isEmpty());
        assertEquals(0, c.day());
    }

    // ── Manager isolation ─────────────────────────────────────

    @Test
    void managerInstancesKeepConversationsSeparate() {
        ConversationManager a = new ConversationManager();
        ConversationManager b = new ConversationManager();
        Conversation ca = a.forRole("ceo");
        Conversation cb = b.forRole("ceo");
        assertNotNull(ca);
        assertNotNull(cb);
        ca.appendTaskExchange(1, "Task", "Answer", null);
        assertTrue(cb.isEmpty(), "conversations of different managers must not interfere");
        assertEquals(1, a.activeCount());
        // key helper
        assertEquals("ceo", ConversationManager.keyFor("ceo", "Lin Zong"));
        assertEquals("Lin Zong", ConversationManager.keyFor("", "Lin Zong"));
        assertEquals("agent", ConversationManager.keyFor("", ""));
    }
}
