package com.agent.software.llm;

import com.agent.software.llm.context.AssistantMessage;
import com.agent.software.llm.context.Context;
import com.agent.software.llm.context.Message;
import com.agent.software.llm.context.ToolMessage;
import com.agent.software.llm.context.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 上下文：remember 过滤、忘掉、按天、快照与多态恢复。 */
class ContextTest {

    @Test
    void rememberControlsPromptButNotHistory() {
        Context ctx = new Context();
        ctx.add(new UserMessage("hello"));
        ctx.add(new AssistantMessage("hi"));
        ctx.add(new ToolMessage("call-1", "run_command", "ok"));

        assertEquals(3, ctx.messages().size());
        assertEquals(3, ctx.history().size());
        assertTrue(ctx.estimatedTokens() > 0);

        Message first = ctx.history().get(0);
        ctx.forget(first);
        assertEquals(2, ctx.messages().size());
        assertEquals(3, ctx.history().size());

        ctx.forgetAll();
        assertEquals(0, ctx.messages().size());
        assertEquals(3, ctx.history().size());
    }

    @Test
    void snapshotRestoresConcreteMessageTypes() {
        Context ctx = new Context();
        ctx.add(new UserMessage("u"));
        ctx.add(new AssistantMessage("a", List.of(java.util.Map.of("id", "1")), "why"));
        ctx.add(new ToolMessage("1", "get_time", "now"));

        Context restored = new Context();
        restored.loadData(ctx.getData());

        assertEquals(3, restored.history().size());
        assertInstanceOf(UserMessage.class, restored.history().get(0));
        assertInstanceOf(AssistantMessage.class, restored.history().get(1));
        assertInstanceOf(ToolMessage.class, restored.history().get(2));
        AssistantMessage a = (AssistantMessage) restored.history().get(1);
        assertEquals(1, a.toolCalls.size());
        assertEquals("why", a.reasoning);
    }

    @Test
    void dayAdvances() {
        Context ctx = new Context();
        assertEquals(1, ctx.getDay());
        ctx.endDay(1);
        assertEquals(2, ctx.getDay());
        ctx.endDay(1);
        assertEquals(2, ctx.getDay());
    }
}
