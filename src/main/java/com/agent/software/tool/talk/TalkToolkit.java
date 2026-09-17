package com.agent.software.tool.talk;

import com.agent.software.agent.AgentDirectory;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.Toolkit;
import com.agent.software.transcript.Transcript;

import java.util.List;

/**
 * 角色间沟通工具包（id {@code "talk"}），暴露工具：talk（组内私聊/委派等待）与 list_roles（花名册只读视图）。
 */
public final class TalkToolkit implements Toolkit {

    private final TeamChannel team;
    private final AgentDirectory directory;
    private final Transcript transcript;

    public TalkToolkit(TeamChannel team, AgentDirectory directory, Transcript transcript) {
        this.team = team;
        this.directory = directory;
        this.transcript = transcript;
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
