package com.agent.software.ports;

/** Result of a tool invocation. A failed result carries a human-readable reason. */
public record ToolResult(boolean ok, String text) {

    public ToolResult {
        text = text == null ? "" : text;
    }

    public static ToolResult success(String text) {
        return new ToolResult(true, text);
    }

    public static ToolResult error(String text) {
        return new ToolResult(false, text);
    }
}
