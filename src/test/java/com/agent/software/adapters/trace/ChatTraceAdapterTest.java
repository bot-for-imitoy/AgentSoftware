package com.agent.software.adapters.trace;

import com.agent.software.domain.Payload;
import com.agent.software.domain.TaskStatus;
import com.agent.software.kernel.RoleId;
import com.agent.software.kernel.TaskId;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolResult;
import com.agent.software.adapters.web.ChatStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTraceAdapterTest {

    private static final RoleId CEO = RoleId.of("CEO");

    @Test
    void recordsEveryTraceKindWithStructuredExtra() {
        ChatStore store = new ChatStore();
        ChatTraceAdapter trace = new ChatTraceAdapter(store);

        trace.reason(CEO, 1, "thinking");
        trace.note(CEO, 1, "about to call a tool");
        trace.tool(CEO, 1, new ToolCall("c1", "echo", Payload.of("text", "hi")),
                ToolResult.success("pong"));
        trace.answer(CEO, TaskId.of("t1"), TaskStatus.DONE, 12, "finished");
        trace.notice("System paused");

        List<Map<String, Object>> messages = store.messagesSince(0);
        assertEquals(5, messages.size());

        assertEquals(ChatStore.KIND_REASON, messages.get(0).get("kind"));
        assertEquals(ChatStore.KIND_NOTE, messages.get(1).get("kind"));

        Map<String, Object> toolMsg = messages.get(2);
        assertEquals(ChatStore.KIND_TOOL, toolMsg.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> toolExtra = (Map<String, Object>) toolMsg.get("extra");
        assertEquals("echo", toolExtra.get("tool"));
        assertEquals("pong", toolExtra.get("result"));

        Map<String, Object> answerMsg = messages.get(3);
        assertEquals(ChatStore.KIND_ANSWER, answerMsg.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> answerExtra = (Map<String, Object>) answerMsg.get("extra");
        assertEquals("done", answerExtra.get("status"));
        assertEquals(12, answerExtra.get("tokens"));
        assertEquals("t1", answerExtra.get("taskId"));

        assertEquals("System", messages.get(4).get("fromName"));
    }

    @Test
    void blankTraceTextIsIgnored() {
        ChatStore store = new ChatStore();
        ChatTraceAdapter trace = new ChatTraceAdapter(store);
        trace.reason(CEO, 1, "   ");
        trace.note(CEO, 1, null);
        assertTrue(store.messagesSince(0).isEmpty());
    }
}
