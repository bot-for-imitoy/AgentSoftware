package com.agent.software.model;

import com.agent.software.kernel.JsonSchema;

/**
 * 工具的对外声明（名字、描述、参数 schema）。
 *
 * <p>放在 model 而非 ports：它是领域数据，且 {@link Message} 需要引用 {@link ToolCallRequest}，
 * 若放在 ports 会造成 model → ports 的反向依赖。
 */
public record ToolSpec(String name, String description, JsonSchema schema) {
}
