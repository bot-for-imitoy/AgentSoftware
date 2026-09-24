package com.agent.software.llm.context;

import com.agent.software.llm.Embedding;
import com.agent.software.llm.OpenAICompatEmbedding;
import com.agent.software.store.ConfigStore;
import com.agent.software.utils.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义记忆：给上下文的每条消息算向量，条数超阈值时按"离新消息最远"淘汰，并支持按语义检索。
 *
 * <p>三条规则：
 * <ol>
 *   <li><b>入上下文即算向量</b>：{@link Context#add} 是唯一的写入口（{@code LLM.append*} 全走它），
 *       所以挂在这里就覆盖了"角色每次添加内容"。</li>
 *   <li><b>阈值淘汰</b>：remember=true 的消息条数超过 {@link #threshold()} 时，把其中"加权距离"
 *       最大的一条的 {@code remember} 置 false。加权距离 = 语义距离 × (1 - 位置邻近权重)，
 *       位置邻近权重随"离刚加入那条的绝对距离"衰减（越近权重越大）—— 也就是**只移出 prompt**，
 *       消息本体和向量都留在内存里，之后还能被 {@code search_memory} 检索到。</li>
 *   <li><b>成组淘汰</b>：不能只丢一条 {@code assistant(tool_calls)} 或一条 {@code tool} 结果，
 *       否则 prompt 里会出现"tool 结果没有对应的 tool_call"或反之，网关直接 400。
 *       所以淘汰时会连带把配对的 tool_call / tool 结果一起移出 prompt。</li>
 * </ol>
 *
 * <p>没有配置 embedding 模型时整体空转（{@link #enabled()} 为 false）：不算向量、不淘汰，
 * 行为和加这个功能之前完全一样。
 *
 * <p>线程契约：只被本角色的 worker 线程调用。
 */
public class SemanticMemory {

    private static final Logger logger = LoggerFactory.getLogger(SemanticMemory.class);

    /** 阈值配置键 / 环境变量。 */
    public static final String THRESHOLD_KEY = "memory.threshold";
    public static final String THRESHOLD_ENV = "AGENTSOFTWARE_MEMORY_THRESHOLD";
    public static final int DEFAULT_THRESHOLD = 40;

    private final Embedding embedding;
    private final int threshold;
    private volatile boolean degradedLogged = false;

    public SemanticMemory(Embedding embedding, int threshold) {
        this.embedding = embedding;
        this.threshold = threshold < 1 ? DEFAULT_THRESHOLD : threshold;
    }

    /** 按配置建一个（{@code embedding.*} + {@code memory.threshold}）。 */
    public static SemanticMemory fromConfig(ConfigStore config) {
        return new SemanticMemory(new OpenAICompatEmbedding(null, null, config), thresholdOf(config));
    }

    /** 阈值：{@code AGENTSOFTWARE_MEMORY_THRESHOLD} &gt; {@code memory.threshold} &gt; 默认 40。 */
    public static int thresholdOf(ConfigStore config) {
        String env = System.getenv(THRESHOLD_ENV);
        if (env != null && !env.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(env.trim()));
            } catch (NumberFormatException ignored) {
                logger.warn("{} is not an integer: {}", THRESHOLD_ENV, env);
            }
        }
        if (config != null) {
            Object v = config.get(THRESHOLD_KEY, null);
            if (v != null) {
                try {
                    return Math.max(1, Integer.parseInt(String.valueOf(v).trim()));
                } catch (NumberFormatException ignored) {
                    logger.warn("{} is not an integer: {}", THRESHOLD_KEY, v);
                }
            }
        }
        return DEFAULT_THRESHOLD;
    }

    /** 语义记忆是否可用（配了 embedding 模型且还没被熔断）。 */
    public boolean enabled() {
        return embedding != null && embedding.isConfigured();
    }

    public int threshold() {
        return threshold;
    }

    public Embedding embedding() {
        return embedding;
    }

    // ── 写入 ────────────────────────────────────────────────────

    /**
     * 一条消息进入上下文后调用：算向量；条数超阈值就淘汰离它最远的一条。
     *
     * <p>失败（没配置/调用失败/空文本）时静默跳过，绝不影响任务本身。
     */
    public void onAdded(Context context, Message added) {
        if (context == null || added == null || !enabled()) {
            return;
        }
        double[] v = added.embedding;
        if (v == null || v.length == 0) {
            String text = textOf(added);
            if (text.isBlank()) {
                return;   // 空消息（如只有 tool_calls 的 assistant）没有语义可言，省一次请求
            }
            v = embedding.embed(text);
            if (v == null || v.length == 0) {
                if (!degradedLogged) {
                    degradedLogged = true;
                    logger.warn("Semantic memory degraded: embedding model '{}' returned no vector; "
                            + "messages will not be indexed", embedding.getModel());
                }
                return;
            }
            added.embedding = v;
        }
        evictFurthest(context, added, v);
    }

    /**
     * 位置邻近权重：离当前消息越近越大（0 → 1.0，1 → 0.5，2 → 0.33，5 → 0.17…）。
     *
     * <p>它作用在"加权距离"上作为**保护**：越近，加权距离被压得越小，越不容易被裁。
     */
    static double proximityWeight(int distance) {
        return 1.0 / (1.0 + Math.max(0, distance));
    }

    /**
     * 淘汰"加权距离"最大的一条。
     *
     * <pre>
     *   语义距离 = 1 - cos(候选, 当前)
     *   加权距离 = 语义距离 × (1 - 位置邻近权重)      // 邻近权重越近越大
     * </pre>
     *
     * 于是"又旧又跑题"的先被移出 prompt；只旧不跑题（语义近）或只跑题不旧（位置近）的都能留下 ——
     * 这就是"离当前消息越近权重越大"的落点。
     */
    private void evictFurthest(Context context, Message added, double[] addedVector) {
        List<Message> remembered = context.messages();
        if (remembered.size() <= threshold) {
            return;
        }
        List<Message> all = context.all();
        int addedAt = all.indexOf(added);
        if (addedAt < 0) {
            addedAt = all.size() - 1;
        }
        Message victim = null;
        double worst = Double.NEGATIVE_INFINITY;
        double worstSemantic = 0;
        int worstDistance = 0;
        for (int i = 0; i < all.size(); i++) {
            Message m = all.get(i);
            if (!m.remember || m == added || m.embedding == null || m.embedding.length == 0) {
                continue;   // 只管 prompt 里的；不淘汰刚加进来的那条；没向量的没法比
            }
            double sim = cosine(addedVector, m.embedding);
            if (Double.isNaN(sim)) {
                continue;
            }
            int distance = Math.abs(i - addedAt);
            double semanticDistance = 1.0 - sim;
            double weighted = semanticDistance * (1.0 - proximityWeight(distance));
            if (weighted > worst) {
                worst = weighted;
                victim = m;
                worstSemantic = semanticDistance;
                worstDistance = distance;
            }
        }
        if (victim == null) {
            return;
        }
        Set<Message> group = evictionGroup(context, victim);
        int forgotten = 0;
        for (Message m : group) {
            if (m.remember) {
                m.remember = false;
                forgotten++;
            }
        }
        if (forgotten > 0) {
            logger.info("Semantic memory: context over threshold {} ({} remembered), forgot {} message(s) "
                            + "with the largest weighted distance (semantic {} x proximity, {} message(s) back)",
                    threshold, remembered.size(), forgotten,
                    String.format("%.3f", worstSemantic), worstDistance);
        }
    }

    /**
     * 淘汰一组消息，保证 prompt 里 {@code assistant(tool_calls)} 与 {@code tool} 结果不会一半在、一半不在。
     *
     * <p>被选中的是 tool 结果时，要连带它的 assistant 调用者（以及那个调用者的其它 tool 结果）；
     * 被选中的是带 tool_calls 的 assistant 时，要连带它所有的 tool 结果。
     */
    static Set<Message> evictionGroup(Context context, Message victim) {
        Set<Message> group = new LinkedHashSet<>();
        group.add(victim);
        if (victim instanceof AssistantMessage a && !callIds(a).isEmpty()) {
            addToolResults(context, group, callIds(a));
        } else if (victim instanceof ToolMessage t) {
            for (Message m : context.all()) {
                if (m instanceof AssistantMessage a && callIds(a).contains(t.toolCallId)) {
                    group.add(a);
                    addToolResults(context, group, callIds(a));
                }
            }
        }
        return group;
    }

    private static void addToolResults(Context context, Set<Message> group, Set<String> callIds) {
        for (Message m : context.all()) {
            if (m instanceof ToolMessage t && callIds.contains(t.toolCallId)) {
                group.add(m);
            }
        }
    }

    private static Set<String> callIds(AssistantMessage a) {
        Set<String> ids = new LinkedHashSet<>();
        if (a == null || a.toolCalls == null) {
            return ids;
        }
        for (Map<String, Object> call : a.toolCalls) {
            Object id = call.get("id");
            if (id == null || String.valueOf(id).isBlank()) {
                id = call.get("call_id");
            }
            if (id != null && !String.valueOf(id).isBlank()) {
                ids.add(String.valueOf(id));
            }
        }
        return ids;
    }

    // ── 检索 ────────────────────────────────────────────────────

    /** 一条命中：消息 + 与查询的余弦相似度（1 = 完全相同语义）。 */
    public record Hit(Message message, double similarity) {
    }

    /**
     * 按语义找最近的记忆 —— 搜的是**全部**历史消息，包含已经 {@code remember=false}
     * （不在 prompt 里但仍在内存中）的那些。
     */
    public List<Hit> search(Context context, String query, int limit) {
        List<Hit> hits = new ArrayList<>();
        if (context == null || !enabled() || query == null || query.isBlank()) {
            return hits;
        }
        double[] qv = embedding.embed(query);
        if (qv == null || qv.length == 0) {
            if (!degradedLogged) {
                degradedLogged = true;
                logger.warn("Semantic memory search failed: embedding model '{}' returned no vector",
                        embedding.getModel());
            }
            return hits;
        }
        for (Message m : context.all()) {
            if (m.embedding == null || m.embedding.length == 0) {
                continue;
            }
            double sim = cosine(qv, m.embedding);
            if (!Double.isNaN(sim)) {
                hits.add(new Hit(m, sim));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::similarity).reversed());
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    // ── 工具 ────────────────────────────────────────────────────

    /** 余弦相似度；任一向量为空/维度不一致/零向量时返回 NaN（表示"没法比"）。 */
    public static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return Double.NaN;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return Double.NaN;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 用于算向量的文本：正文为空时退化成 tool_calls 的名字+参数。 */
    static String textOf(Message m) {
        String content = m.content == null ? "" : m.content;
        if (!content.isBlank()) {
            return Text.truncate(content, 8000);
        }
        if (m instanceof AssistantMessage a && a.toolCalls != null && !a.toolCalls.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> call : a.toolCalls) {
                Object fn = call.get("function");
                if (fn instanceof Map<?, ?> f) {
                    sb.append(f.get("name")).append(' ').append(f.get("arguments")).append('\n');
                }
            }
            return sb.toString().strip();
        }
        return "";
    }
}
