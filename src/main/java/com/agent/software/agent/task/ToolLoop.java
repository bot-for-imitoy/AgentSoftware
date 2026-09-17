package com.agent.software.agent.task;

import com.agent.software.agent.dialog.ConversationMemory;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Payload;
import com.agent.software.llm.LlmClient;
import com.agent.software.llm.Message;
import com.agent.software.llm.ToolCallRequest;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolbox;
import com.agent.software.transcript.Transcript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一轮任务里的 LLM ↔ 工具循环（从 master {@code AgentRole.executeWithTools} 抽出）。
 *
 * <p>只负责循环本身：请求 → 执行工具 → 回填 tool 消息 → 直到不再有工具调用；
 * 上限与失败策略来自 {@link ToolLoopPolicy}，轨迹写 {@link Transcript}。
 */
public final class ToolLoop {

    private static final Logger logger = LoggerFactory.getLogger(ToolLoop.class);

    /** 工具回执里参数/结果的截断长度（对齐 master {@code Conversation} 的常量）。 */
    private static final int RECAP_ARGS_MAX = 80;
    private static final int RECAP_RESULT_MAX = 200;
    private static final int RECAP_LIMIT = 6;

    private final LlmClient llm;
    private final Toolbox toolbox;
    private final Transcript transcript;
    private final ToolLoopPolicy policy;

    public ToolLoop(LlmClient llm, Toolbox toolbox, Transcript transcript, ToolLoopPolicy policy) {
        this.llm = llm;
        this.toolbox = toolbox;
        this.transcript = transcript;
        this.policy = policy;
    }

    /** 轨迹写入端口（{@code agent.Agent} 建 TaskRunner 时要用）。 */
    public Transcript transcript() {
        return transcript;
    }

    public Outcome run(RoleId agent, String systemPrompt, Task task,
                       ConversationMemory memory, int day) {
        List<ToolSpec> specs = toolbox == null ? List.of() : toolbox.specs();
        int totalTokens = 0;
        try {
            // 没有任何工具：退回单轮 chat（对齐 master RolePool 的无工具分支）
            if (specs.isEmpty()) {
                LlmClient.ChatReply reply = llm.chat(new LlmClient.ChatRequest(
                        systemPrompt, task.description(), 0.7, 512));
                totalTokens += reply.tokens();
                if (reply.reasoning() != null && !reply.reasoning().isBlank()
                        && !reply.reasoning().equals(reply.text())) {
                    transcript.reasoning(agent, reply.reasoning(), meta(task, null));
                }
                if (reply.failed()) {
                    return new Outcome(reply.text(), totalTokens, true);
                }
                memory.commit(day, task.description(), reply.text(), llm);
                return new Outcome(reply.text(), totalTokens, false);
            }

            List<Message> messages = memory.prepare(systemPrompt, task.description(), day);
            List<String> recaps = new ArrayList<>();
            int round = 0;
            while (true) {
                round++;
                if (policy.roundBudgetExceeded(round)) {
                    return new Outcome("[ERROR] 工具调用超过 " + policy.maxRounds()
                            + " 轮仍未收敛（共 " + totalTokens + " tokens）", totalTokens, true);
                }
                LlmClient.ToolReply reply = llm.chatWithTools(
                        new LlmClient.ToolChatRequest(messages, specs, 0.7, null));
                totalTokens += reply.totalTokens();
                if (policy.tokenBudgetExceeded(totalTokens)) {
                    return new Outcome("[ERROR] 任务累计消耗 " + totalTokens + " tokens，超过预算 "
                            + policy.maxTotalTokens(), totalTokens, true);
                }
                String content = reply.content() == null ? "" : reply.content();
                if (reply.reasoning() != null && !reply.reasoning().isBlank()) {
                    transcript.reasoning(agent, reply.reasoning(), meta(task, round));
                }
                List<ToolCallRequest> calls = reply.toolCalls() == null ? List.of() : reply.toolCalls();
                if (calls.isEmpty()) {
                    if (content.isBlank()) {
                        // 没有任何内容也没有工具调用 = LLM 调用失败，绝不能当成成功
                        return new Outcome("[ERROR] LLM 在第 " + round + " 轮返回空内容", totalTokens, true);
                    }
                    memory.commit(day, task.description(), enrich(content, recaps), llm);
                    return new Outcome(content, totalTokens, false);
                }
                if (!content.isBlank()) {
                    transcript.note(agent, content, meta(task, round));
                }
                messages.add(Message.assistant(content.isEmpty() ? null : content, calls));
                for (ToolCallRequest call : calls) {
                    String argsJson = json(call.arguments());
                    ToolResult result = invoke(call);
                    transcript.toolCall(agent, call.toolName(), argsJson, result.text(), meta(task, round));
                    collectRecap(recaps, call.toolName(), argsJson, result.text());
                    messages.add(Message.tool(call.callId(), result.text()));
                    logger.debug("[{}] 工具调用 {}({}) → {}", agent.value(), call.toolName(),
                            argsJson, com.agent.software.kernel.Text.truncate(result.text(), 80));
                }
            }
        } catch (RuntimeException e) {
            String msg = "[ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage();
            logger.error("[{}] 工具循环异常（已消耗 {} tokens）", agent.value(), totalTokens, e);
            // failOnLlmError=false 时把错误当成任务的最终结果，任务本身不算失败
            return new Outcome(msg, totalTokens, policy.failOnLlmError());
        }
    }

    private ToolResult invoke(ToolCallRequest call) {
        try {
            ToolResult result = toolbox.invoke(call.toolName(), call.arguments());
            return result == null ? ToolResult.error("工具 " + call.toolName() + " 没有返回结果") : result;
        } catch (RuntimeException e) {
            logger.warn("工具 {} 执行失败：{}", call.toolName(), e.getMessage());
            return ToolResult.error("工具 " + call.toolName() + " 执行失败：" + e.getMessage());
        }
    }

    private static Transcript.TraceMeta meta(Task task, Integer round) {
        return new Transcript.TraceMeta(task.id(), round);
    }

    /**
     * 记一条有界的工具回执（最多 {@value #RECAP_LIMIT} 条，之后只留一个"还有更多"标记），
     * 供 {@link #enrich} 附在提交给对话的 assistant 文本后面。
     */
    private static void collectRecap(List<String> recaps, String toolName, String argsJson, String result) {
        if (recaps.size() < RECAP_LIMIT) {
            recaps.add(toolName + "(" + com.agent.software.kernel.Text.truncate(argsJson, RECAP_ARGS_MAX)
                    + ") → " + com.agent.software.kernel.Text.truncate(result, RECAP_RESULT_MAX));
        } else if (!recaps.contains("…")) {
            recaps.add("… and more tool calls during this task");
        }
    }

    /** 提交给对话的 assistant 文本 = 最终答复 + 工具活动回执。 */
    private static String enrich(String answer, List<String> recaps) {
        String body = answer == null ? "" : answer;
        if (recaps == null || recaps.isEmpty()) {
            return body;
        }
        String recap = "[During this task I used tools: " + String.join("; ", recaps) + "]";
        return body.isEmpty() ? recap : body + "\n\n" + recap;
    }

    /**
     * 把类型化参数渲染成紧凑 JSON（只用于轨迹展示与回执）。
     *
     * <p>故意不依赖 {@code infra.json}：agent 包不认识 Jackson，展示用的序列化在这里
     * 自己写 20 行就够了。
     */
    static String json(Payload payload) {
        Map<String, Object> map = payload == null ? Map.of() : payload.asMap();
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':').append(value(e.getValue()));
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static String value(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String s) {
            return quote(s);
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        if (v instanceof Map<?, ?> m) {
            return json(Payload.ofMap((Map<String, Object>) m));
        }
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(value(list.get(i)));
            }
            return sb.append(']').toString();
        }
        return quote(String.valueOf(v));
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    public record Outcome(String answer, int tokens, boolean failed) {
    }
}
