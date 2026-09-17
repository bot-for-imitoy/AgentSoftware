package com.agent.software.tool.note;

import com.agent.software.agent.AgentControl;
import com.agent.software.sim.clock.Clock;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.Toolkit;

import java.util.List;

/**
 * 记忆工具包（id {@code "memory"}），暴露工具：summary（保存当日总结、转 OFF_DUTY、关闭当日对话，并关闭个人电脑）。
 */
public final class MemoryToolkit implements Toolkit {

    private final NoteBook notes;
    private final Clock clock;
    private final AgentControl control;

    public MemoryToolkit(NoteBook notes, Clock clock, AgentControl control) {
        this.notes = notes;
        this.clock = clock;
        this.control = control;
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
