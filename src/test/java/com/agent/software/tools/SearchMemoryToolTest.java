package com.agent.software.tools;

import com.agent.software.AgentSystem;
import com.agent.software.io.WebInput;
import com.agent.software.llm.context.Context;
import com.agent.software.llm.context.FakeEmbedding;
import com.agent.software.llm.context.SemanticMemory;
import com.agent.software.llm.context.UserMessage;
import com.agent.software.role.Role;
import com.agent.software.tools.toolkits.memory.Memory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** search_memory：语义检索历史记忆（含已被挤出 prompt 的），以及未启用时的说法。 */
class SearchMemoryToolTest {

    private static FakeEmbedding embedder() {
        return new FakeEmbedding(new double[0])
                .put("alpha", new double[]{1, 0})
                .put("beta", new double[]{0, 1})
                .put("gamma", new double[]{-1, 0});
    }

    @Test
    void returnsTheNearestMemoriesIncludingForgottenOnes(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            Context context = ceo.getContext();
            context.setMemory(new SemanticMemory(embedder(), 2));
            context.add(new UserMessage("alpha"));
            context.add(new UserMessage("beta"));
            context.add(new UserMessage("gamma"));   // 超阈值 → 离 gamma 最远的 alpha 被移出 prompt
            assertEquals(2, context.messages().size());
            assertEquals(3, context.history().size());

            Memory toolkit = new Memory(ceo);
            List<String> tools = toolkit.getTools().stream().map(Tool::getToolName).toList();
            assertEquals(List.of("search_memory"), tools);

            String out = toolkit.trigger("search_memory", Map.of("query", "alpha"));
            assertTrue(out.contains("alpha"), out);
            assertTrue(out.contains("similarity 1.000"), out);
            assertTrue(out.contains("[forgotten]"), "被挤出 prompt 的记忆也必须能被搜到: " + out);
            String hits = out.substring(out.indexOf("1. ["));
            assertTrue(hits.indexOf("alpha") < hits.indexOf("beta"),
                    "最相似的要排在第一条前面: " + out);

            String limited = toolkit.trigger("search_memory", Map.of("query", "alpha", "limit", "1"));
            assertTrue(limited.contains("top 1 of 3"), limited);
        } finally {
            system.stop();
        }
    }

    @Test
    void saysWhenSemanticMemoryIsNotConfigured(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");   // 测试配置里没有 embedding.model
            Memory toolkit = new Memory(ceo);
            String out = toolkit.trigger("search_memory", Map.of("query", "anything"));
            assertTrue(out.contains("not enabled"), out);
        } finally {
            system.stop();
        }
    }

    @Test
    void validatesArguments(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            ceo.getContext().setMemory(new SemanticMemory(embedder(), 40));
            Memory toolkit = new Memory(ceo);
            assertTrue(toolkit.trigger("search_memory", Map.of()).contains("needs a query"));
            assertTrue(toolkit.trigger("search_memory", Map.of("query", "  ")).contains("needs a query"));
            assertTrue(toolkit.trigger("search_memory", Map.of("query", "alpha", "limit", "abc"))
                    .contains("not an integer"));
            assertTrue(toolkit.trigger("search_memory", Map.of("query", "nothing-like-this"))
                    .contains("nothing matched"));
        } finally {
            system.stop();
        }
    }
}
