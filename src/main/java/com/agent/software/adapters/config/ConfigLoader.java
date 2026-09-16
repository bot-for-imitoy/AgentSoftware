package com.agent.software.adapters.config;

/**
 * 唯一配置入口，优先级 env &gt; config.json &gt; 代码默认。
 */
public final class ConfigLoader {

    /** 加载并按优先级合并三层配置。 */
    public AppConfig load() {
        throw new UnsupportedOperationException("skeleton");
    }
}
