package com.agent.software.llm.context;

import com.agent.software.utils.Data;
import com.agent.software.utils.Json;
import com.agent.software.utils.UUIDObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条对话消息的抽象基类。
 *
 * <p>按你的要求保留"三个子类分开"的设计，但它们必须是**独立文件**：原骨架把它们写成
 * 抽象类的非静态内部类，那样永远无法实例化。
 *
 * <p>字段不是 final：持久化恢复靠 {@link #loadData(Map)} 回填。
 */
public abstract class Message extends UUIDObject implements Data {

    public String content;
    /** 只影响是否进 prompt，不影响是否留在内存。 */
    public boolean remember = true;
    public long timestamp = System.currentTimeMillis();
    /**
     * 这条消息的语义向量（由 {@code llm.Embedding} 算出）。
     * {@code null} 或长度 0 = 还没算 / 算不出来（embedding 未配置或调用失败）。
     */
    public double[] embedding;

    protected Message() {
    }

    protected Message(String content) {
        this.content = content == null ? "" : content;
    }

    protected Message(String uuid, String content) {
        super(uuid);
        this.content = content == null ? "" : content;
    }

    /** system / user / assistant / tool */
    public abstract String getRole();

    /** 持久化类型标记，供 {@code DataRegistry} 多态恢复。 */
    public abstract String type();

    @Override
    public Map<String, String> getData() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("data_type", type());
        d.put("uuid", uuid);
        d.put("role", getRole());
        d.put("content", content == null ? "" : content);
        d.put("remember", Boolean.toString(remember));
        d.put("timestamp", Long.toString(timestamp));
        if (embedding != null && embedding.length > 0) {
            d.put("embedding", Json.stringify(embedding));
        }
        return d;
    }

    @Override
    public void loadData(Map<String, String> data) {
        if (data == null) {
            return;
        }
        if (data.containsKey("uuid")) {
            this.uuid = data.get("uuid");
        }
        this.content = data.getOrDefault("content", "");
        this.remember = Boolean.parseBoolean(data.getOrDefault("remember", "true"));
        this.timestamp = parseLong(data.get("timestamp"), this.timestamp);
        this.embedding = parseEmbedding(data.get("embedding"));
    }

    /** 反序列化向量；缺失/畸形都当成"没有向量"。 */
    private static double[] parseEmbedding(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            List<Object> nums = Json.parseArray(raw);
            if (nums.isEmpty()) {
                return null;
            }
            double[] out = new double[nums.size()];
            for (int i = 0; i < nums.size(); i++) {
                Object o = nums.get(i);
                out[i] = o instanceof Number n ? n.doubleValue() : 0.0;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    protected static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + (content == null ? "" : content) + ")";
    }
}
