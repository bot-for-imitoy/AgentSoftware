package com.agent.software.tools.builtin;

import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.ToolContext;
import com.agent.software.tools.spi.Toolkit;

import java.util.List;

/**
 * Hermes 工具包（id {@code "hermes"}，默认不装配），暴露工具：hermes_send / hermes_new_conversation（经 {@link ToolContext#shell()} 调用外部 Hermes 通道）。
 */
public final class HermesToolkit implements Toolkit {

    public HermesToolkit() {
    }

    @Override
    public String id() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Tool> instantiate(ToolContext context) {
        throw new UnsupportedOperationException("skeleton");
    }
}
