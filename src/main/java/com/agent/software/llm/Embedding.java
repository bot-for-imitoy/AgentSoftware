package com.agent.software.llm;

/**
 * Embedding（语义向量）模型客户端抽象 —— 与 {@link LLM} 对称的那个"请求类"，
 * 只是请求的不是补全而是向量。
 *
 * <p>约定（和 {@code LLM} 一样不抛异常、把失败变成可判断的返回值）：
 * <ul>
 *   <li>未配置 / 空文本 / 调用失败 → 返回**长度为 0** 的数组，绝不为了一条消息把任务搞崩；</li>
 *   <li>{@link #isConfigured()} 为 false 时上层（{@code SemanticMemory}）直接跳过，
 *       不去撞一个不存在的能力（网关不支持 embeddings 时每次撞墙都会拖慢每条消息的写入）。</li>
 * </ul>
 *
 * <p>典型实现：{@link OpenAICompatEmbedding}（POST {@code {base_url}/embeddings}）。
 */
public abstract class Embedding {

    /** 长度为 0 的空向量，表示"没有向量"。 */
    public static final double[] EMPTY = new double[0];

    /** embedding 模型名，例如 {@code text-embedding-3-small}。 */
    public abstract String getModel();

    /** 服务地址（含 /v1）。 */
    public abstract String getEndpoint();

    /** 计算一条文本的语义向量；不可用时返回 {@link #EMPTY}。 */
    public abstract double[] embed(String text);

    /** 是否配置了可用的 embedding 模型。 */
    public boolean isConfigured() {
        return true;
    }

    /** 最近一次成功拿到的向量维度；不知道时返回 0。 */
    public int dimension() {
        return 0;
    }
}
