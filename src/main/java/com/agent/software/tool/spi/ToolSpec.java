package com.agent.software.tool.spi;

import com.agent.software.kernel.JsonSchema;

/**
 * 工具的对外声明（名字、描述、参数 schema）。
 *
 * <p>工具描述归工具层所有：{@code llm.LlmClient.ToolChatRequest} 会引用它，
 * 因此依赖方向是 llm → tool.spi，反过来不成立。
 */
public record ToolSpec(String name, String description, JsonSchema schema) {
}
