package com.agent.software.tool.computer;

import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.Toolkit;

import java.util.List;

/**
 * 个人电脑工具包（id {@code "pc"}），暴露工具：run_command / computer_status / lan_devices / reboot（经由构造期注入的 {@link Shell} 操作电脑）。
 */
public final class PcToolkit implements Toolkit {

    private final Shell shell;

    public PcToolkit(Shell shell) {
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
