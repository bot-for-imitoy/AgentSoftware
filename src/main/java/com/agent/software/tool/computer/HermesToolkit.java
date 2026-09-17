package com.agent.software.tool.computer;

import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.Toolkit;

import java.util.List;

/**
 * Hermes 工具包（id {@code "hermes"}，默认不装配），暴露工具：hermes_send / hermes_new_conversation（经由构造期注入的 {@link Shell} 调用外部 Hermes 通道）。
 */
public final class HermesToolkit implements Toolkit {

    private final Shell shell;

    public HermesToolkit(Shell shell) {
        this.shell = shell;
    }

    @Override
    public String id() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Tool> instantiate() {
        throw new UnsupportedOperationException("skeleton");
    }
}
