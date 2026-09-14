package com.agent.software.runtime;

import com.agent.software.kernel.AgentException;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ChatMessage;
import com.agent.software.ports.LlmPort;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolPort;
import com.agent.software.ports.ToolResult;
import com.agent.software.ports.TracePort;

import java.util.ArrayList;
import java.util.List;

/**
 * The LLM ↔ tool loop for one task.
 *
 * <p>Unlike the legacy {@code AgentRole.executeWithTools}, the round budget and
 * token budget are a mandatory {@link Policy}: exceeding either raises a
 * {@link AgentException.DomainException} that the runtime turns into a failed
 * task. Tool calls are executed sequentially and fed back as {@code role=tool}
 * messages linked by call id.
 */
public final class ToolLoop {

    /** Hard limits for one task. */
    public record Policy(int maxRounds, Integer maxTokens) {
        public Policy {
            if (maxRounds < 1) {
                throw new IllegalArgumentException("maxRounds must be >= 1, got " + maxRounds);
            }
            if (maxTokens != null && maxTokens < 1) {
                throw new IllegalArgumentException("maxTokens must be >= 1 when set");
            }
        }

        public static Policy defaults() {
            return new Policy(20, null);
        }
    }

    /** Final answer plus the tokens spent across all rounds. */
    public record Outcome(String answer, int tokens) {
        public Outcome {
            answer = answer == null ? "" : answer;
        }
    }

    private final LlmPort llm;
    private final ToolPort tools;
    private final TracePort trace;
    private final Policy policy;

    public ToolLoop(LlmPort llm, ToolPort tools, TracePort trace, Policy policy) {
        this.llm = llm;
        this.tools = tools;
        this.trace = trace;
        this.policy = policy == null ? Policy.defaults() : policy;
    }

    public Outcome run(RoleId role, String systemPrompt, String taskDescription) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        messages.add(ChatMessage.user(taskDescription == null ? "" : taskDescription));

        int totalTokens = 0;
        for (int round = 1; round <= policy.maxRounds(); round++) {
            LlmPort.ToolReply reply = llm.chatWithTools(
                    new LlmPort.ToolRequest(messages, tools.specs(role), 0.7, null));
            totalTokens += reply.tokens();
            trace.reason(role, round, reply.reasoning());

            if (reply.failed()) {
                throw new AgentException.PortException(
                        "LLM call failed in round " + round + ": " + reply.content());
            }
            if (!reply.hasToolCalls()) {
                return new Outcome(reply.content(), totalTokens);
            }

            trace.note(role, round, reply.content());
            messages.add(ChatMessage.assistant(reply.content(), reply.toolCalls()));
            for (ToolCall call : reply.toolCalls()) {
                ToolResult result = tools.invoke(role, call);
                trace.tool(role, round, call, result);
                messages.add(ChatMessage.toolResult(call.id(), result.text()));
            }

            if (policy.maxTokens() != null && totalTokens > policy.maxTokens()) {
                throw new AgentException.DomainException(
                        "tool loop token budget exceeded: " + totalTokens + " > " + policy.maxTokens());
            }
        }
        throw new AgentException.DomainException(
                "tool loop exceeded " + policy.maxRounds() + " rounds without converging");
    }

    public Policy policy() {
        return policy;
    }
}
