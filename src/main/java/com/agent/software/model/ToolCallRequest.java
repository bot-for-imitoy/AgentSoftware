package com.agent.software.model;

/**
 * 一次工具调用请求（LLM 产生的 function call）。
 *
 * @param callId   provider 给出的调用标识，用于把结果回填成 tool 消息
 * @param toolName 工具名
 * @param arguments 类型化参数
 */
public record ToolCallRequest(String callId, String toolName, Payload arguments) {
}
