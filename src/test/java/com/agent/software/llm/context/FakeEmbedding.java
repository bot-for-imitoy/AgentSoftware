package com.agent.software.llm.context;

import com.agent.software.llm.Embedding;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试用 Embedding：文本 → 预先指定的向量（查表，查不到用 fallback）。
 *
 * <p>用查表而不是真算，是为了让"谁离谁更远"完全可控，断言才好写。
 */
public class FakeEmbedding extends Embedding {

    private final Map<String, double[]> table = new LinkedHashMap<>();
    private final double[] fallback;
    private boolean configured = true;
    private int calls = 0;

    public FakeEmbedding(double[] fallback) {
        this.fallback = fallback == null ? new double[0] : fallback;
    }

    public FakeEmbedding put(String text, double[] vector) {
        table.put(text, vector);
        return this;
    }

    public FakeEmbedding configured(boolean value) {
        this.configured = value;
        return this;
    }

    public int calls() {
        return calls;
    }

    @Override
    public String getModel() {
        return "fake-embed";
    }

    @Override
    public String getEndpoint() {
        return "fake://";
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public double[] embed(String text) {
        calls++;
        double[] v = table.get(text == null ? "" : text);
        return v == null ? fallback : v;
    }
}
