package com.agent.software.tool.talk;

import com.agent.software.agent.Team;
import com.agent.software.agent.WaitCoordinator;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.agent.task.Task;
import com.agent.software.transcript.Transcript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * {@link TeamChannel} 的 engine 实现：talk 工具的运行时后端。
 *
 * <p>wait=true 的语义在这里：用发送者的 {@link WaitCoordinator} 进入等待，
 * 把委派任务投给目标角色，收到回复或超时后结束等待。master 中这套逻辑直接写在
 * {@code TalkTo} + {@code AgentRole.talkWait} 里，工具因此穿透到了角色内部。
 */
public final class TalkService implements TeamChannel {

    private final Team team;
    private final Transcript transcript;

    public TalkService(Team team, Transcript transcript) {
        this.team = team;
        this.transcript = transcript;
    }

    @Override
    public List<RoleSpec> roster() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public Optional<RoleSpec> findByName(String personName) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void send(TalkMessage message) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public Optional<String> sendAndWait(TalkMessage message, Task delegated, Duration timeout) {
        throw new UnsupportedOperationException("skeleton");
    }
}
