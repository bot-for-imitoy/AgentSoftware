package com.agent.software.tools.builtin;

import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.ToolContext;
import com.agent.software.tools.spi.Toolkit;

import java.util.List;

/**
 * 个人电脑工具包（id {@code "pc"}），暴露工具：run_command / computer_status / lan_devices / reboot（经 {@link ToolContext#shell()} 操作电脑）。
 */
public final class PcToolkit implements Toolkit {

    public PcToolkit() {
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
