package com.agent.software.infra.config;

import java.util.Set;

/**
 * 全局配置的不可变快照：LLM、排班、存储、Web、邮件、工具包六块配置的唯一载体。
 */
public record AppConfig(Llm llm, Schedule schedule, Storage storage, Web web, Mail mail, Toolkits toolkits) {

    /** 代码内置默认配置（env 与 config.json 均未提供时的兜底）。 */
    public static AppConfig defaults() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** LLM 接入配置。 */
    public record Llm(String providerId, String model, String apiKey, String baseUrl, Retry retry) {

        /** LLM 调用重试与超时配置。 */
        public record Retry(int maxAttempts, double delaySeconds, int timeoutSeconds) {
        }
    }

    /** 时间推进与班次配置。 */
    public record Schedule(double secondsPerTick, int shiftStartHour, int shiftEndHour,
                           long fastForwardIdleMillis, double simSecondsPerRealSecond,
                           long wrapUpGraceMillis) {
    }

    /** 存储目录配置。 */
    public record Storage(String dataDir) {
    }

    /** Web 服务配置。 */
    public record Web(String host, int port, long replyTimeoutMillis) {
    }

    /** 公司邮箱配置：虚拟邮箱后缀 + 可选真实 SMTP。 */
    public record Mail(String suffix, Smtp smtp) {

        /** 真实 SMTP 发送配置。 */
        public record Smtp(String host, int port, String user, String password, String from, boolean useSsl) {
        }
    }

    /** 默认装配的工具包集合。 */
    public record Toolkits(Set<String> defaults) {
    }
}
