package com.agent.software.llm.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 语义记忆：入上下文即算向量、超阈值淘汰最远的一条（成组淘汰）、按语义检索（含 forgotten）。 */
class SemanticMemoryTest {

    private static final double[] RIGHT = {1, 0};
    private static final double[] UP = {0, 1};
    private static final double[] LEFT = {-1, 0};
    private static final double[] DOWN = {0, -1};

    private static FakeEmbedding embedder() {
        return new FakeEmbedding(new double[0])
                .put("alpha", RIGHT)
                .put("beta", UP)
                .put("gamma", LEFT)
                .put("delta", DOWN);
    }

    private static UserMessage msg(String text) {
        return new UserMessage(text);
    }

    @Test
    void everyAddedMessageGetsAnEmbedding() {
        SemanticMemory memory = new SemanticMemory(embedder(), 40);
        Context context = new Context();
        context.setMemory(memory);

        UserMessage m = msg("alpha");
        context.add(m);
        assertNotNull(m.embedding, "入上下文就要算向量");
        assertEquals(2, m.embedding.length);
        assertTrue(m.remember);

        // 已有向量的消息不重复请求（恢复持久化数据时靠这条避免 N 次调用）
        int before = ((FakeEmbedding) memory.embedding()).calls();
        UserMessage restored = msg("alpha");
        restored.embedding = RIGHT.clone();
        context.add(restored);
        assertEquals(before, ((FakeEmbedding) memory.embedding()).calls(), "已有向量不应再请求");
    }

    @Test
    void forgetsTheMessageFurthestFromTheNewOneOnlyWhenOverThreshold() {
        SemanticMemory memory = new SemanticMemory(embedder(), 3);
        Context context = new Context();
        context.setMemory(memory);

        UserMessage a = msg("alpha");
        UserMessage b = msg("beta");
        UserMessage c = msg("gamma");
        context.add(a);
        context.add(b);
        context.add(c);
        assertEquals(3, context.messages().size());
        assertTrue(a.remember && b.remember && c.remember, "没超过阈值不应淘汰");

        UserMessage d = msg("delta");   // 与 alpha=0、beta=-1、gamma=0
        context.add(d);
        assertFalse(b.remember, "离 delta 最远的是 beta（相似度 -1）");
        assertTrue(a.remember && c.remember && d.remember, "只淘汰一条，且不动刚加入的那条");
        assertEquals(3, context.messages().size(), "淘汰只改标记，不删消息");
        assertEquals(4, context.history().size());
    }

    @Test
    void closestVectorIsNotAlwaysTheVictim() {
        SemanticMemory memory = new SemanticMemory(embedder(), 2);
        Context context = new Context();
        context.setMemory(memory);
        UserMessage a = msg("alpha");   // (1,0)
        UserMessage b = msg("beta");    // (0,1)
        UserMessage c = msg("gamma");   // (-1,0)
        context.add(a);
        context.add(b);
        context.add(c);                 // 超阈值：与 gamma 最远的是 alpha（-1）
        assertFalse(a.remember);
        assertTrue(b.remember && c.remember);
    }

    @Test
    void toolCallAndItsResultsAreForgottenTogether() {
        SemanticMemory memory = new SemanticMemory(embedder(), 2);
        Context context = new Context();
        context.setMemory(memory);

        List<Map<String, Object>> calls = new ArrayList<>();
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "c1");
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "run_command");
        function.put("arguments", "{}");
        call.put("function", function);
        calls.add(call);

        AssistantMessage assistant = new AssistantMessage("alpha", calls, "");
        ToolMessage tool = new ToolMessage("c1", "run_command", "beta");
        UserMessage fresh = msg("gamma");
        context.add(assistant);
        context.add(tool);
        context.add(fresh);   // 超阈值：与 gamma(-1,0) 最远的正是 assistant(1,0)

        assertFalse(assistant.remember, "被选中的 assistant 要移出 prompt");
        assertFalse(tool.remember,
                "它的 tool 结果必须一起移出，否则 prompt 里 tool 结果没有对应的 tool_call，网关会 400");
        assertTrue(fresh.remember);
        assertEquals(1, context.messages().size());
    }

    @Test
    void forgettingAToolResultAlsoForgetsItsCaller() {
        SemanticMemory memory = new SemanticMemory(embedder(), 2);
        Context context = new Context();
        context.setMemory(memory);

        List<Map<String, Object>> calls = new ArrayList<>();
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "c9");
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "read_mail");
        function.put("arguments", "{}");
        call.put("function", function);
        calls.add(call);

        AssistantMessage assistant = new AssistantMessage("beta", calls, "");   // (0,1)
        ToolMessage tool = new ToolMessage("c9", "read_mail", "gamma");        // (-1,0)
        UserMessage fresh = msg("beta");                                       // (0,1)
        context.add(assistant);
        context.add(tool);
        context.add(fresh);   // 与 beta(0,1) 最远的是 tool(-1,0)

        assertFalse(tool.remember);
        assertFalse(assistant.remember, "反过来也要成组：tool_call 不能孤零零留在 prompt 里");
    }

    /**
     * 位置邻近权重真的能改变淘汰结果：纯看余弦会丢"最不相似的那条"，
     * 加权后"离得近但也跑题"的那条反而先出局，"又旧又跑题"的留下。
     */
    @Test
    void proximityWeightingCanChangeWhichMessageIsForgotten() {
        FakeEmbedding embedder = new FakeEmbedding(new double[0])
                .put("far", new double[]{-0.4, Math.sqrt(1 - 0.16)})    // cos=-0.4（离得远，d=3）
                .put("near", new double[]{-0.8, Math.sqrt(1 - 0.64)})   // cos=-0.8（离得近，d=1）
                .put("same", new double[]{1, 0})                        // cos=1
                .put("current", new double[]{1, 0});
        SemanticMemory memory = new SemanticMemory(embedder, 3);
        Context context = new Context();
        context.setMemory(memory);

        UserMessage far = msg("far");
        UserMessage same = msg("same");
        UserMessage near = msg("near");
        UserMessage current = msg("current");
        context.add(far);      // 位置 0：语义距离 1.4 × (1-0.25) = 1.05  ← 加权后最大
        context.add(same);     // 位置 1：语义距离 0    × …      = 0
        context.add(near);     // 位置 2：语义距离 1.8 × (1-0.5) = 0.9
        context.add(current);  // 位置 3（当前）

        // 纯余弦会淘汰 near（-0.8 最不相似）；加权位置后淘汰的是 far。
        assertFalse(far.remember, "又旧又跑题的应先出局: far");
        assertTrue(near.remember, "离当前近的即使话题偏一点也留下: near");
        assertTrue(same.remember && current.remember);
    }

    @Test
    void searchFindsNearestMemoriesIncludingForgottenOnes() {
        SemanticMemory memory = new SemanticMemory(embedder(), 2);
        Context context = new Context();
        context.setMemory(memory);
        UserMessage a = msg("alpha");
        UserMessage b = msg("beta");
        UserMessage c = msg("gamma");
        context.add(a);
        context.add(b);
        context.add(c);   // 超阈值 → alpha 被移出 prompt
        assertFalse(a.remember);

        List<SemanticMemory.Hit> hits = memory.search(context, "alpha", 3);
        assertEquals(3, hits.size());
        assertSame(a, hits.get(0).message(), "忘了但还记着：alpha 仍应是最近的一条");
        assertEquals(1.0, hits.get(0).similarity(), 1e-9);
        assertEquals(-1.0, hits.get(2).similarity(), 1e-9);

        List<SemanticMemory.Hit> top1 = memory.search(context, "alpha", 1);
        assertEquals(1, top1.size());
        assertSame(a, top1.get(0).message());
    }

    @Test
    void withoutAnEmbeddingModelNothingHappens() {
        FakeEmbedding disabled = embedder().configured(false);
        SemanticMemory memory = new SemanticMemory(disabled, 2);
        Context context = new Context();
        context.setMemory(memory);

        assertFalse(memory.enabled());
        UserMessage a = msg("alpha");
        context.add(a);
        assertNull(a.embedding, "没配 embedding 时不算向量");
        assertEquals(0, disabled.calls());
        context.add(msg("beta"));
        context.add(msg("gamma"));
        assertTrue(a.remember, "没有向量就没法比距离，不淘汰");
        assertTrue(memory.search(context, "alpha", 3).isEmpty());
    }

    @Test
    void cosineHandlesDegenerateVectors() {
        assertEquals(1.0, SemanticMemory.cosine(RIGHT, RIGHT), 1e-9);
        assertEquals(0.0, SemanticMemory.cosine(RIGHT, UP), 1e-9);
        assertEquals(-1.0, SemanticMemory.cosine(RIGHT, LEFT), 1e-9);
        assertTrue(Double.isNaN(SemanticMemory.cosine(null, RIGHT)));
        assertTrue(Double.isNaN(SemanticMemory.cosine(RIGHT, new double[0])));
        assertTrue(Double.isNaN(SemanticMemory.cosine(new double[]{0, 0}, RIGHT)), "零向量不比较");
        assertTrue(Double.isNaN(SemanticMemory.cosine(RIGHT, new double[]{1, 0, 0})), "维度不一致不比较");
    }

    @Test
    void thresholdComesFromConfigOrDefault() {
        assertEquals(SemanticMemory.DEFAULT_THRESHOLD, SemanticMemory.thresholdOf(null));
    }
}
