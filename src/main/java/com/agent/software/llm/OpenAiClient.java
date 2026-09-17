package com.agent.software.llm;

import com.agent.software.infra.config.AppConfig;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 唯一 LLM 实现：OpenAI 兼容 chat/completions，内置重试、限流排队与余额不足处理。
 */
public final class OpenAiClient implements LlmClient {

    /** 以解析好的 endpoint、重试配置与共享仲裁器构造。 */
    public OpenAiClient(ProviderResolver.Endpoint endpoint, AppConfig.Llm.Retry retry, RetryArbiter arbiter) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public ChatReply chat(ChatRequest request) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public ToolReply chatWithTools(ToolChatRequest request) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public ChatReply summarize(String text, double temperature, int maxTokens) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 注入全局暂停门：返回 true 时等待而不发起请求。 */
    public void setPausedGate(Supplier<Boolean> gate) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 注入余额不足通知（例如暂停公司并提示充值）。 */
    public void setOnInsufficientBalance(Consumer<String> listener) {
        throw new UnsupportedOperationException("skeleton");
    }
}
