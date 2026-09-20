package com.agent.software.agent.dialog;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.llm.LlmClient;
import com.agent.software.llm.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConversationMemory} 当日对话记忆测试。
 *
 * <p>合并 master 的 {@code ConversationTest}、{@code ConversationEndToEndTest} 与
 * {@code ConversationStateStoreTest} 中"记忆本身"的那部分：跨任务连续性、跨天自动清空、
 * 下班 {@code closeDay} 后重开、超预算压缩、压缩失败退化、snapshot/restore 往返。
 *
 * <p>master 的进程级 {@code ConversationManager} 在新架构里被有意去掉（每个 {@code Agent}
 * 各持一份 {@code ConversationMemory}），因此测试里不再断言 manager。
 * master 走真实 worker（{@code RolePool.roleLoop}）验证的"工具回执回灌""summary 工具关闭当日
 * 对话"属于 {@code agent.task.TaskRunner} / {@code agent.Agent} 的职责，不在这里重复。
 */
class ConversationMemoryTest {

    /** 可脚本化的摘要 LLM：{@code summary} 为 null 时返回空白（等价于失败）。 */
    private static final class FakeLlm implements LlmClient {
        private final String summary;
        private int summarizeCalls;

        FakeLlm(String summary) {
            this.summary = summary;
        }

        @Override
        public ChatReply chat(ChatRequest request) {
            return new ChatReply("", null, 0);
        }

        @Override
        public ToolReply chatWithTools(ToolChatRequest request) {
            return new ToolReply("", null, List.of(), 0);
        }

        @Override
        public ChatReply summarize(String text, double temperature, int maxTokens) {
            summarizeCalls++;
            return new ChatReply(summary == null ? "" : summary, null, 0);
        }
    }

    private static ConversationMemory memory(int maxHistoryChars) {
        return memory(maxHistoryChars, 6);
    }

    /** recapLimit 同时是"压缩失败时至少保留的最近消息条数"（见 ConversationPolicy）。 */
    private static ConversationMemory memory(int maxHistoryChars, int recapLimit) {
        return new ConversationMemory(new RoleId("ceo"),
                new ConversationPolicy(maxHistoryChars, 12_000, recapLimit));
    }

    private static long chars(ConversationMemory memory) {
        long total = 0;
        for (Message message : memory.snapshot().messages()) {
            if (!message.forgotten()) {
                total += message.content() == null ? 0 : message.content().length();
            }
        }
        return total;
    }

    // ── 跨任务连续性 ────────────────────────────────────────────

    @Test
    void prepare只组装系统提示与当前任务() {
        ConversationMemory memory = memory(24_000);

        List<Message> messages = memory.prepare("You are the CEO", "Task one", 1);

        assertEquals(2, messages.size());
        assertEquals(Message.Role.SYSTEM, messages.get(0).role());
        assertEquals("You are the CEO", messages.get(0).content());
        assertEquals(Message.Role.USER, messages.get(1).role());
        assertEquals("Task one", messages.get(1).content());
        assertTrue(memory.isEmpty(), "prepare 不写入任何东西");
    }

    @Test
    void 提交的交换带入下一个任务() {
        ConversationMemory memory = memory(24_000);
        memory.prepare("sys", "Task one", 1);
        memory.commit(1, "Task one", "Answer one", null);

        List<Message> messages = memory.prepare("sys", "Task two", 1);

        // system + 已提交的 [user task1, assistant answer1] + 新 user task
        assertEquals(4, messages.size());
        assertEquals("Task one", messages.get(1).content());
        assertEquals("Answer one", messages.get(2).content());
        assertEquals("Task two", messages.get(3).content());
        assertEquals(2, memory.historySize());

        // prepare 返回的是快照，改动它不影响内部历史
        messages.clear();
        assertEquals(2, memory.historySize());
    }

    @Test
    void 空交换不写入历史() {
        ConversationMemory memory = memory(24_000);
        memory.commit(1, "", "", null);
        assertTrue(memory.isEmpty());
        assertEquals(0, memory.historySize());
    }

    @Test
    void 多次任务后仍可继续prepare() {
        ConversationMemory memory = memory(24_000);
        memory.commit(1, "Collect requirements", "Requirements noted", null);
        memory.commit(1, "Reply to the client", "Plan sent", null);

        List<Message> prepared = memory.prepare("sys", "Next task", 1);

        assertEquals(6, prepared.size(), "system + 4 条历史 + 新任务");
    }

    // ── 天 / 下班生命周期 ───────────────────────────────────────

    @Test
    void 跨天自动清空() {
        ConversationMemory memory = memory(24_000);
        memory.commit(1, "Day one task", "Answer", null);
        assertEquals(2, memory.historySize());

        List<Message> nextDay = memory.prepare("sys", "Day two task", 2);

        // 旧对话被丢弃；"昨天的总结"经 system prompt 注入（见 SystemPromptTest）
        assertEquals(2, nextDay.size());
        assertEquals(Message.Role.SYSTEM, nextDay.get(0).role());
        assertEquals(Message.Role.USER, nextDay.get(1).role());
        assertEquals("Day two task", nextDay.get(1).content());
        assertEquals(0, memory.historySize());
        assertEquals(2, memory.snapshot().day());
    }

    @Test
    void 下班关闭后同天prepare重开并清空() {
        ConversationMemory memory = memory(24_000);
        memory.commit(1, "Work", "Answer", null);
        memory.closeDay(1);
        assertTrue(memory.isEmpty());
        assertEquals(1, memory.snapshot().closedDay());

        List<Message> reopened = memory.prepare("sys", "Emergency task", 1);

        assertEquals(2, reopened.size(), "重开后不得带出旧上下文");
        assertTrue(memory.isEmpty());
        assertEquals(-1, memory.snapshot().closedDay());
    }

    @Test
    void 关闭当天的收尾交换不会被写入() {
        ConversationMemory memory = memory(24_000);
        memory.commit(1, "Work", "Answer", null);
        memory.closeDay(1);

        memory.commit(1, "SHIFT_END summary", "Summary saved", null);

        assertTrue(memory.isEmpty());
        assertEquals(0, memory.historySize());
    }

    @Test
    void 关闭后新的一天重新开始() {
        ConversationMemory memory = memory(24_000);
        memory.commit(1, "Work", "Answer", null);
        memory.closeDay(1);

        memory.commit(2, "New day task", "New answer", null);

        assertEquals(2, memory.historySize());
        assertEquals(2, memory.snapshot().day());
        assertEquals(-1, memory.snapshot().closedDay());
    }

    // ── 自动压缩 ────────────────────────────────────────────────

    @Test
    void 超预算时用摘要压缩成一条消息() {
        FakeLlm llm = new FakeLlm("MERGED: everything earlier.");
        ConversationMemory memory = memory(120);
        String big = "details details details details details details details details";

        memory.commit(1, "Big task " + big, "Big answer " + big, llm);

        assertEquals(1, llm.summarizeCalls);
        assertEquals(1, memory.activeHistorySize());
        Message only = memory.snapshot().messages().stream()
                .filter(message -> !message.forgotten()).findFirst().orElseThrow();
        assertEquals(Message.Role.USER, only.role());
        assertTrue(only.content().startsWith("[Earlier dialogue summary"), only.content());
        assertTrue(only.content().contains("MERGED: everything earlier."));
        assertTrue(chars(memory) <= 120, "压缩后必须落在预算内，实际 " + chars(memory));
    }

    @Test
    void 摘要失败时退化为丢弃最旧消息() {
        FakeLlm llm = new FakeLlm(null); // 空白回复 = 失败
        ConversationMemory memory = memory(50, 2);
        String message = "0123456789abcdefghij"; // 20 字符

        for (int i = 0; i < 5; i++) {
            memory.commit(1, message, message, llm);
        }

        assertEquals(4, llm.summarizeCalls);
        assertTrue(chars(memory) <= 50, "即使摘要失败上下文也必须缩小，实际 " + chars(memory));
        assertTrue(memory.historySize() >= 1);
        for (Message m : memory.snapshot().messages()) {
            assertFalse(m.content().startsWith("[Earlier dialogue summary"),
                    "摘要失败时不得插入合成摘要消息");
        }
    }

    @Test
    void 无LLM时也会收缩上下文() {
        ConversationMemory memory = memory(50, 2);
        String message = "0123456789abcdefghij";
        for (int i = 0; i < 5; i++) {
            memory.commit(1, message, message, null);
        }
        assertTrue(chars(memory) <= 50, "上下文必须缩小，实际 " + chars(memory));
    }

    // ── 序列化 ──────────────────────────────────────────────────

    @Test
    void snapshot与restore往返() {
        ConversationMemory memory = memory(24_000);
        memory.commit(1, "Hire a developer", "Posted the job", null);
        memory.commit(1, "Review candidates", "Shortlisted three", null);

        ConversationMemory.State state = memory.snapshot();
        assertEquals(1, state.day());
        assertEquals(4, state.messages().size());

        ConversationMemory restored = memory(24_000);
        restored.restore(state);

        assertEquals(4, restored.historySize());
        assertEquals(state, restored.snapshot());
        Message third = restored.snapshot().messages().get(2);
        assertEquals(Message.Role.USER, third.role());
        assertEquals("Review candidates", third.content());
        assertEquals("Shortlisted three", restored.snapshot().messages().get(3).content());
    }

    @Test
    void restore空档时清空并保持空() {
        ConversationMemory memory = memory(24_000);
        memory.commit(1, "Work", "Answer", null);

        memory.restore(null);

        assertTrue(memory.isEmpty());
        assertEquals(0, memory.historySize());
    }

    @Test
    void 超过消息上限时遗忘与当前消息距离最远的一条() {
        ConversationPolicy policy = new ConversationPolicy(24_000, 12_000, 6, 3);
        ConversationMemory memory = new ConversationMemory(new RoleId("ceo"), policy, text -> switch (text) {
            case "old-related", "old-related-answer" -> List.of(1.0, 0.0);
            case "current", "current-answer" -> List.of(0.0, 1.0);
            default -> List.of(0.5, 0.5);
        });

        memory.commit(1, "old-related", "old-related-answer", null);
        memory.commit(1, "current", "current-answer", null);

        assertEquals(4, memory.historySize(), "遗忘消息仍应保存在状态中");
        assertEquals(3, memory.activeHistorySize());
        Message forgotten = memory.snapshot().messages().get(0);
        assertTrue(forgotten.forgotten());
        assertEquals(List.of(1.0, 0.0), forgotten.embedding());

        List<Message> prompt = memory.prepare("sys", "next", 1);
        assertFalse(prompt.stream().anyMatch(message -> "old-related".equals(message.content())));
        assertTrue(prompt.stream().anyMatch(message -> "old-related-answer".equals(message.content())));
        assertTrue(prompt.stream().anyMatch(message -> "current".equals(message.content())));
    }

    @Test
    void 向量失败时遗忘最旧的活动消息() {
        ConversationPolicy policy = new ConversationPolicy(24_000, 12_000, 6, 2);
        ConversationMemory memory = new ConversationMemory(new RoleId("ceo"), policy,
                text -> { throw new java.io.IOException("offline"); });

        memory.commit(1, "one", "two", null);
        memory.commit(1, "three", "", null);

        assertTrue(memory.snapshot().messages().get(0).forgotten());
        assertEquals(2, memory.activeHistorySize());
    }
}
