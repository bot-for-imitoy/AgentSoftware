package com.agent.software.tool.spi;

/** 一次工具调用的结果（显式 error 标志，替代字符串前缀嗅探）。 */
public record ToolResult(String text, boolean error) {

    public static ToolResult ok(String text) {
        return new ToolResult(text == null ? "" : text, false);
    }

    public static ToolResult error(String text) {
        return new ToolResult(text == null ? "" : text, true);
    }
}
