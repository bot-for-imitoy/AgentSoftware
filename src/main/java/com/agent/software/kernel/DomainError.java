package com.agent.software.kernel;

/**
 * 领域层统一非受检异常。
 *
 * <p>目的：失败必须用"类型 + code"表达，禁止再用 {@code "[API error:"} 之类的字符串前缀嗅探
 * （master 的 {@code LLM.LLM_ERROR_MARKERS} / {@code Types.isFailureText} 即此问题）。
 */
public final class DomainError extends RuntimeException {

    /** 机器可读的错误分类，例：{@code "llm.timeout"} / {@code "tool.not-found"}。 */
    private final String code;

    public DomainError(String code, String message) {
        super(message);
        this.code = code;
    }

    public DomainError(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
