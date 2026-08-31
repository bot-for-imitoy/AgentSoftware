package com.agent.software.tools.toolkits.pc;

import com.maf.scheduler.core.Computer;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * reboot — 重启个人电脑 (关机后自动开机). 适合清理运行状态或安装工具后重启.
 */
public class Reboot extends Tool {

    private final Computer computer;

    public Reboot(Computer computer) {
        super();
        this.computer = computer;
    }

    @Override
    public String getToolName() {
        return "reboot";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String handler(Map<String, Object> args) {
        return this.computer.reboot();
    }
}
