package com.agent.software.adapters.llm;

import java.util.List;
import java.util.Optional;

/**
 * 供应商目录：从 classpath 的 providers.default.json 加载，并叠加可选的本地覆盖文件。
 */
public final class ProviderCatalog {

    /** 全部供应商定义（本地覆盖合并后的结果）。 */
    public List<ProviderDef> all() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 按 id 查找供应商。 */
    public Optional<ProviderDef> find(String id) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 一个供应商的静态定义。 */
    public record ProviderDef(String id, String name, String baseUrl, String apiKeyEnv,
                              String defaultModel, boolean enabled) {
    }
}
