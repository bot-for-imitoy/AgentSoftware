package com.agent.software.agent.task;

import com.agent.software.agent.AgentMailbox;
import com.agent.software.agent.AgentRuntime;
import com.agent.software.agent.AgentState;
import com.agent.software.agent.LifecycleGate;
import com.agent.software.agent.dialog.ConversationMemory;
import com.agent.software.agent.dialog.ConversationPolicy;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TaskId;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.llm.LlmClient;
import com.agent.software.llm.Message;
import com.agent.software.llm.ToolCallRequest;
import com.agent.software.sim.event.EventKind;
import com.agent.software.sim.event.Priority;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolbox;
import com.agent.software.transcript.Transcript;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具循环（{@link ToolLoop}）与单角色执行器（{@link TaskRunner}）的轨迹测试。
 *
 * <p>master 对应 {@code role.AgentRoleTraceTest}：当时通过拉起整个 {@code AgentSystem}
 * + 真实 worker 验证"reason → tool → reason → answer"的 Web 轨迹。新架构把循环抽成了
 * 纯对象，所以这里用假 {@link LlmClient} + 假 {@link Toolbox} + 记录型 {@link Transcript}
 * 直接驱动，不再起公司（更快、更确定）。
 */
class ToolLoopTest {

    private static final RoleId ROLE = new RoleId("backend_dev_1");

    // ── 假实现 ─────────────────────────────────────────────────

    /** 记录每一次轨迹写入，供断言检查轮次与内容。 */
    private static final class RecordingTranscript implements Transcript {

        final List<String> reasons = new ArrayList<>();
        final List<Integer> reasonRounds = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<Integer> noteRounds = new ArrayList<>();
        final List<String> toolNames = new ArrayList<>();
        final List<String> toolArgs = new ArrayList<>();
        final List<String> toolResults = new ArrayList<>();
        final List<Integer> toolRounds = new ArrayList<>();
        final List<String> answers = new ArrayList<>();
        final List<Boolean> answerFailed = new ArrayList<>();
        final List<Integer> answerTokens = new ArrayList<>();
        final List<String> answerTaskIds = new ArrayList<>();
        final List<String> systems = new ArrayList<>();

        @Override
        public void reasoning(RoleId agent, String text, TraceMeta meta) {
            reasons.add(text);
            reasonRounds.add(meta == null ? null : meta.round());
        }

        @Override
        public void note(RoleId agent, String text, TraceMeta meta) {
            notes.add(text);
            noteRounds.add(meta == null ? null : meta.round());
        }

        @Override
        public void toolCall(RoleId agent, String toolName, String argsJson, String result, TraceMeta meta) {
            toolNames.add(toolName);
            toolArgs.add(argsJson);
            toolResults.add(result);
            toolRounds.add(meta == null ? null : meta.round());
        }

        @Override
        public void answer(RoleId agent, String text, boolean failed, int tokens, TraceMeta meta) {
            answers.add(text);
            answerFailed.add(failed);
            answerTokens.add(tokens);
            answerTaskIds.add(meta == null || meta.taskId() == null ? null : meta.taskId().value());
        }

        @Override
        public void talk(Talk record) {
        }

        @Override
        public void client(Client record) {
        }

        @Override
        public void system(String text) {
            systems.add(text);
        }
    }

    /** 只暴露一个工具的工具箱；invoke 全部记录并可脚本化返回。 */
    private static final class FakeToolbox implements Toolbox {

        private final List<ToolSpec> specs;
        private final List<String> invoked = new ArrayList<>();
        private final List<Payload> invokedArgs = new ArrayList<>();
        private ToolResult result = ToolResult.ok("10:00 am");

        FakeToolbox(ToolSpec... specs) {
            this.specs = List.of(specs);
        }

        @Override
        public List<ToolSpec> specs() {
            return specs;
        }

        @Override
        public ToolResult invoke(String toolName, Payload arguments) {
            invoked.add(toolName);
            invokedArgs.add(arguments);
            return result;
        }
    }

    /** 按调用序号返回脚本化回复的 LLM；可以抛异常验证 failOnLlmError 两种行为。 */
    private static final class ScriptedLlm implements LlmClient {

        private final List<ToolReply> toolReplies;
        private ChatReply chatReply = new LlmClient.ChatReply("直接回答", "先想一想", 3);
        private RuntimeException toolError;
        private int toolCalls;
        private int chats;
        private final List<ToolChatRequest> toolRequests = new ArrayList<>();

        ScriptedLlm(ToolReply... toolReplies) {
            this.toolReplies = List.of(toolReplies);
        }

        @Override
        public ChatReply chat(ChatRequest request) {
            chats++;
            return chatReply;
        }

        @Override
        public ToolReply chatWithTools(ToolChatRequest request) {
            toolRequests.add(request);
            toolCalls++;
            if (toolError != null) {
                throw toolError;
            }
            return toolReplies.get(Math.min(toolCalls - 1, toolReplies.size() - 1));
        }

        @Override
        public ChatReply summarize(String text, double temperature, int maxTokens) {
            return chatReply;
        }
    }

    /** TaskRunner 只需要的运行时能力。 */
    private static final class FakeRuntime implements AgentRuntime {

        private final RoleId id;
        private final ConversationMemory conversation;
        private final List<Task> pending = new ArrayList<>();
        private final List<Task> finished = new ArrayList<>();

        FakeRuntime(RoleId id) {
            this.id = id;
            this.conversation = new ConversationMemory(id, ConversationPolicy.defaults());
        }

        @Override
        public RoleId id() {
            return id;
        }

        @Override
        public String systemPrompt() {
            return "你是工程师";
        }

        @Override
        public ConversationMemory conversation() {
            return conversation;
        }

        @Override
        public int currentDay() {
            return 1;
        }

        @Override
        public List<Task> pending() {
            return new ArrayList<>(pending);
        }

        @Override
        public List<Task> history(int limit) {
            return new ArrayList<>(finished);
        }

        @Override
        public void beginTask(Task task) {
            pending.remove(task);
        }

        @Override
        public void finishTask(Task task) {
            finished.add(task);
        }

        @Override
        public void transitionTo(AgentState next) {
        }

        @Override
        public void closeDayConversation(int day) {
        }

        @Override
        public void powerOffComputer() {
        }
    }

    // ── 完整循环 ───────────────────────────────────────────────

    @Test
    void 工具循环写轨迹并回填tool消息() {
        ToolSpec spec = new ToolSpec("get_time", "获取当前时间", JsonSchema.object());
        FakeToolbox box = new FakeToolbox(spec);
        ScriptedLlm llm = new ScriptedLlm(
                new LlmClient.ToolReply("我先查一下时间", "需要先获取当前时间",
                        List.of(new ToolCallRequest("call-1", "get_time", Payload.empty())), 5),
                new LlmClient.ToolReply("现在是 10:00", "拿到时间了", List.of(), 7));
        RecordingTranscript tx = new RecordingTranscript();
        ConversationMemory memory = new ConversationMemory(ROLE, ConversationPolicy.defaults());
        ToolLoop loop = new ToolLoop(llm, box, tx, ToolLoopPolicy.defaults());
        Task task = task("查一下当前时间");

        ToolLoop.Outcome out = loop.run(ROLE, "你是助手", task, memory, 1);

        assertFalse(out.failed(), "正常收敛不应失败");
        assertTrue(out.answer().contains("10:00"));
        assertEquals(12, out.tokens(), "两轮的 token 应累加");

        // 轨迹：第 1 轮 reasoning + note（有正文且带工具调用）+ toolCall；第 2 轮 reasoning
        assertEquals(List.of("需要先获取当前时间", "拿到时间了"), tx.reasons);
        assertEquals(List.of(1, 2), tx.reasonRounds);
        assertEquals(List.of("我先查一下时间"), tx.notes);
        assertEquals(List.of(1), tx.noteRounds);
        assertEquals(List.of("get_time"), tx.toolNames);
        assertTrue(tx.toolResults.get(0).contains("10:00 am"));
        assertEquals(List.of(1), tx.toolRounds);
        assertEquals("{}", tx.toolArgs.get(0), "空参数应渲染成紧凑 JSON");

        // 工具声明随请求传给 LLM
        assertEquals("get_time", llm.toolRequests.get(0).tools().get(0).name());
        assertEquals(2, llm.toolRequests.size());

        // 第 2 轮请求里必须带上 assistant(tool_calls) 与 tool 回执
        List<Message> second = llm.toolRequests.get(1).messages();
        Message assistant = second.get(second.size() - 2);
        Message toolMessage = second.get(second.size() - 1);
        assertEquals(Message.Role.ASSISTANT, assistant.role());
        assertEquals(1, assistant.toolCalls().size());
        assertEquals("get_time", assistant.toolCalls().get(0).toolName());
        assertEquals(Message.Role.TOOL, toolMessage.role());
        assertEquals("10:00 am", toolMessage.content());
        assertEquals("call-1", toolMessage.toolCallId());

        // 收敛后提交进对话记忆（user + assistant 两条）
        assertEquals(2, memory.historySize());
    }

    @Test
    void 无工具时退回单轮chat() {
        FakeToolbox box = new FakeToolbox();
        ScriptedLlm llm = new ScriptedLlm();
        RecordingTranscript tx = new RecordingTranscript();
        ConversationMemory memory = new ConversationMemory(ROLE, ConversationPolicy.defaults());
        ToolLoop loop = new ToolLoop(llm, box, tx, ToolLoopPolicy.defaults());
        Task task = task("随便聊聊");

        ToolLoop.Outcome out = loop.run(ROLE, "你是助手", task, memory, 1);

        assertFalse(out.failed());
        assertEquals("直接回答", out.answer());
        assertEquals(3, out.tokens());
        assertEquals(0, llm.toolCalls, "没有工具时不应走 chatWithTools");
        assertEquals(1, llm.chats);
        assertEquals(List.of("先想一想"), tx.reasons);
        assertTrue(tx.notes.isEmpty());
        assertTrue(tx.toolNames.isEmpty());
        assertEquals(2, memory.historySize());
    }

    @Test
    void 无工具且LLM空回复视为失败() {
        FakeToolbox box = new FakeToolbox();
        ScriptedLlm llm = new ScriptedLlm();
        llm.chatReply = new LlmClient.ChatReply("", null, 2);
        RecordingTranscript tx = new RecordingTranscript();
        ConversationMemory memory = new ConversationMemory(ROLE, ConversationPolicy.defaults());
        ToolLoop loop = new ToolLoop(llm, box, tx, ToolLoopPolicy.defaults());

        ToolLoop.Outcome out = loop.run(ROLE, "你是助手", task("x"), memory, 1);

        assertTrue(out.failed(), "没有可用文本应判定失败（不再嗅探 '[API error:' 前缀）");
        assertEquals(2, out.tokens());
        assertEquals(0, memory.historySize(), "失败不应写入对话记忆");
    }

    // ── 预算 ───────────────────────────────────────────────────

    @Test
    void 工具轮次超上限触发失败() {
        FakeToolbox box = new FakeToolbox(new ToolSpec("loop", "死循环", JsonSchema.object()));
        ScriptedLlm llm = new ScriptedLlm(
                new LlmClient.ToolReply("继续调用", null,
                        List.of(new ToolCallRequest("c", "loop", Payload.empty())), 1));
        RecordingTranscript tx = new RecordingTranscript();
        ConversationMemory memory = new ConversationMemory(ROLE, ConversationPolicy.defaults());
        ToolLoop loop = new ToolLoop(llm, box, tx, new ToolLoopPolicy(2, 0, true));

        ToolLoop.Outcome out = loop.run(ROLE, "sys", task("x"), memory, 1);

        assertTrue(out.failed());
        assertTrue(out.answer().startsWith("[ERROR]"));
        assertTrue(out.answer().contains("2 轮"), "错误信息应说明轮次上限：" + out.answer());
        assertEquals(2, llm.toolCalls, "第 3 轮进入前就应被上限拦住");
        assertTrue(memory.isEmpty(), "未收敛的任务不应写入对话记忆");
    }

    @Test
    void token预算超限触发失败() {
        FakeToolbox box = new FakeToolbox(new ToolSpec("spend", "烧 token", JsonSchema.object()));
        ScriptedLlm llm = new ScriptedLlm(
                new LlmClient.ToolReply("继续", null, List.of(new ToolCallRequest("c", "spend", Payload.empty())), 10));
        RecordingTranscript tx = new RecordingTranscript();
        ConversationMemory memory = new ConversationMemory(ROLE, ConversationPolicy.defaults());
        ToolLoop loop = new ToolLoop(llm, box, tx, new ToolLoopPolicy(10, 5, true));

        ToolLoop.Outcome out = loop.run(ROLE, "sys", task("x"), memory, 1);

        assertTrue(out.failed());
        assertTrue(out.answer().startsWith("[ERROR]"));
        assertTrue(out.answer().contains("预算"), "错误信息应说明 token 预算：" + out.answer());
        assertEquals(10, out.tokens());
        assertEquals(1, llm.toolCalls, "第一次调用后即超预算，不应再请求");
    }

    @Test
    void 零预算表示不限制() {
        ToolLoopPolicy policy = new ToolLoopPolicy(12, 0, true);
        assertFalse(policy.tokenBudgetExceeded(1_000_000));
        assertFalse(policy.roundBudgetExceeded(12));
        assertTrue(policy.roundBudgetExceeded(13));
    }

    // ── LLM 异常 ───────────────────────────────────────────────

    @Test
    void LLM异常在failOnLlmError为true时任务失败() {
        FakeToolbox box = new FakeToolbox(new ToolSpec("boom", "会炸", JsonSchema.object()));
        ScriptedLlm llm = new ScriptedLlm();
        llm.toolError = new IllegalStateException("API 挂了");
        RecordingTranscript tx = new RecordingTranscript();
        ConversationMemory memory = new ConversationMemory(ROLE, ConversationPolicy.defaults());
        ToolLoop loop = new ToolLoop(llm, box, tx, new ToolLoopPolicy(12, 0, true));

        ToolLoop.Outcome out = loop.run(ROLE, "sys", task("x"), memory, 1);

        assertTrue(out.failed());
        assertTrue(out.answer().startsWith("[ERROR]"));
        assertTrue(out.answer().contains("IllegalStateException"));
        assertTrue(out.answer().contains("API 挂了"));
    }

    @Test
    void LLM异常在failOnLlmError为false时作为最终结果() {
        FakeToolbox box = new FakeToolbox(new ToolSpec("boom", "会炸", JsonSchema.object()));
        ScriptedLlm llm = new ScriptedLlm();
        llm.toolError = new IllegalStateException("API 挂了");
        RecordingTranscript tx = new RecordingTranscript();
        ConversationMemory memory = new ConversationMemory(ROLE, ConversationPolicy.defaults());
        ToolLoop loop = new ToolLoop(llm, box, tx, new ToolLoopPolicy(12, 0, false));

        ToolLoop.Outcome out = loop.run(ROLE, "sys", task("x"), memory, 1);

        assertFalse(out.failed(), "failOnLlmError=false 时任务本身不算失败");
        assertTrue(out.answer().startsWith("[ERROR]"), "但结果文本仍应显式带错误标记");
    }

    // ── 执行器写入 answer 轨迹 ─────────────────────────────────

    @Test
    void TaskRunner写answer轨迹并完成任务() {
        FakeToolbox box = new FakeToolbox();
        ScriptedLlm llm = new ScriptedLlm();
        RecordingTranscript tx = new RecordingTranscript();
        ToolLoop loop = new ToolLoop(llm, box, tx, ToolLoopPolicy.defaults());

        AgentMailbox mailbox = new AgentMailbox();
        FakeRuntime runtime = new FakeRuntime(ROLE);
        TaskRunner runner = new TaskRunner(mailbox, runtime, loop, tx, new LifecycleGate());
        Task task = task("把答案写进轨迹");
        mailbox.push(task, false);

        Thread worker = Thread.ofVirtual().name("test-runner").start(runner);
        try {
            await("worker 写出 answer 轨迹", 5_000, () -> !tx.answers.isEmpty());
        } finally {
            runner.requestStop();
            joinQuietly(worker);
        }

        assertEquals(TaskStatus.DONE, task.status());
        assertEquals("直接回答", task.result());
        assertEquals(1, tx.answers.size());
        assertEquals("直接回答", tx.answers.get(0));
        assertEquals(Boolean.FALSE, tx.answerFailed.get(0));
        assertEquals(3, tx.answerTokens.get(0));
        assertNotNull(tx.answerTaskIds.get(0), "answer 轨迹必须带 taskId");
        assertEquals(task.id().value(), tx.answerTaskIds.get(0));
        assertEquals(1, runtime.finished.size());
    }

    @Test
    void TaskRunner把失败任务写成失败answer() {
        FakeToolbox box = new FakeToolbox(new ToolSpec("boom", "会炸", JsonSchema.object()));
        ScriptedLlm llm = new ScriptedLlm();
        llm.toolError = new IllegalStateException("API 挂了");
        RecordingTranscript tx = new RecordingTranscript();
        ToolLoop loop = new ToolLoop(llm, box, tx, new ToolLoopPolicy(12, 0, true));

        AgentMailbox mailbox = new AgentMailbox();
        FakeRuntime runtime = new FakeRuntime(ROLE);
        TaskRunner runner = new TaskRunner(mailbox, runtime, loop, tx, new LifecycleGate());
        Task task = task("必定失败");
        mailbox.push(task, false);

        Thread worker = Thread.ofVirtual().name("test-runner-fail").start(runner);
        try {
            await("worker 写出失败 answer 轨迹", 5_000, () -> !tx.answers.isEmpty());
        } finally {
            runner.requestStop();
            joinQuietly(worker);
        }

        assertEquals(TaskStatus.FAILED, task.status());
        assertTrue(tx.answers.get(0).startsWith("[ERROR]"));
        assertTrue(task.result().startsWith("[ERROR]"));
        assertEquals(Boolean.TRUE, tx.answerFailed.get(0));
    }

    // ── 助手 ───────────────────────────────────────────────────

    private static Task task(String description) {
        return new Task(TaskId.generate(), Priority.NORMAL, description,
                new EventKind("test", "TASK"), Payload.empty(), Instant.now(), ROLE);
    }

    private static void await(String what, long timeoutMillis, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "等待超时：" + what);
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
