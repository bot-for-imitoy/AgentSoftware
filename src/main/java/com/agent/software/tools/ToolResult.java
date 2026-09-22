package com.agent.software.tools;

/**
 * 工具执行结果：成功与否 + 文本。
 *
 * <p>只有 boolean 不够 —— 工具失败时模型需要看到失败原因才能自我修正，所以文本必须回喂。
 */
public final class ToolResult {

    public final boolean ok;
    public final String text;

    public ToolResult(boolean ok, String text) {
        this.ok = ok;
        this.text = text == null ? "" : text;
    }

    @Override
    public String toString() {
        return "ToolResult(ok=" + ok + ", text=" + text + ")";
    }
}
