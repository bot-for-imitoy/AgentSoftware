package com.agent.software.tools.builtin;

import com.agent.software.ports.Clock;
import com.agent.software.ports.NoteBook;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.ToolContext;
import com.agent.software.tools.spi.Toolkit;

import java.util.List;

/**
 * 记忆工具包（id {@code "memory"}），暴露工具：summary（保存当日总结、转 OFF_DUTY、关闭当日对话，并经 {@link ToolContext#control()} 关闭个人电脑）。
 */
public final class MemoryToolkit implements Toolkit {

    private final NoteBook notes;
    private final Clock clock;

    public MemoryToolkit(NoteBook notes, Clock clock) {
        this.notes = notes;
        this.clock = clock;
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
