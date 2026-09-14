package com.agent.software.runtime;

import com.agent.software.domain.TaskStatus;
import com.agent.software.kernel.RoleId;
import com.agent.software.kernel.TaskId;
import com.agent.software.ports.ChatMessage;
import com.agent.software.ports.LlmPort;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolPort;
import com.agent.software.ports.ToolResult;
import com.agent.software.ports.ToolSpec;
import com.agent.software.ports.TracePort;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared hand-written fakes for runtime tests. */
final class RuntimeFakes {

    private RuntimeFakes() {
    }

    static final class FakeLlm implements LlmPort {
        private final Deque<ToolReply> replies = new ArrayDeque<>();
        final List<ToolRequest> requests = new ArrayList<>();

        FakeLlm(ToolReply... scripted) {
            replies.addAll(List.of(scripted));
        }

        @Override
        public ChatReply chat(ChatRequest request) {
            throw new UnsupportedOperationException("chat not scripted");
        }

        @Override
        public ChatReply summarize(String text, int maxTokens) {
            throw new UnsupportedOperationException("summarize not scripted");
        }

        @Override
        public ToolReply chatWithTools(ToolRequest request) {
            requests.add(request);
            if (replies.isEmpty()) {
                throw new IllegalStateException("no scripted reply left");
            }
            return replies.removeFirst();
        }
    }

    static final class FakeTools implements ToolPort {
        private final Map<String, ToolResult> results = new LinkedHashMap<>();
        final List<ToolCall> calls = new ArrayList<>();

        FakeTools on(String name, ToolResult result) {
            results.put(name, result);
            return this;
        }

        @Override
        public List<ToolSpec> specs(RoleId role) {
            return List.of();
        }

        @Override
        public ToolResult invoke(RoleId role, ToolCall call) {
            calls.add(call);
            return results.getOrDefault(call.name(), ToolResult.error("unknown tool " + call.name()));
        }
    }

    static final class RecordingTrace implements TracePort {
        final List<String> reasons = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<String> tools = new ArrayList<>();
        final List<String> answers = new ArrayList<>();
        final List<String> notices = new ArrayList<>();

        @Override
        public void reason(RoleId role, int round, String text) {
            reasons.add(round + ":" + text);
        }

        @Override
        public void note(RoleId role, int round, String text) {
            notes.add(round + ":" + text);
        }

        @Override
        public void tool(RoleId role, int round, ToolCall call, ToolResult result) {
            tools.add(round + ":" + call.name() + ":" + result.text());
        }

        @Override
        public void answer(RoleId role, TaskId task, TaskStatus status, int tokens, String text) {
            answers.add(status.wireName() + ":" + tokens + ":" + text);
        }

        @Override
        public void notice(String text) {
            notices.add(text);
        }
    }

    static ChatMessage lastUserOrToolMessage(FakeLlm llm) {
        List<ChatMessage> messages = llm.requests.get(llm.requests.size() - 1).messages();
        return messages.get(messages.size() - 1);
    }
}
