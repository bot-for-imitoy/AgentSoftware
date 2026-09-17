package com.agent.software.tool.time;

import com.agent.software.sim.clock.Clock;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.Toolkit;

import java.util.List;

/**
 * 时间工具包（id {@code "time"}），暴露工具：get_time / take_rest。
 */
public final class TimeToolkit implements Toolkit {

    private final Clock clock;

    public TimeToolkit(Clock clock) {
        this.clock = clock;
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
