package com.agent.software.tool.talk;

import com.agent.software.agent.WaitCoordinator;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.agent.task.Task;
import com.agent.software.kernel.Ids.RoleId;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 角色间通信能力（talk 工具的后端）。
 *
 * <p>把 master 中 {@code TalkTo} 直接抓 {@code RolePool}、对方 {@code AgentRole}、
 * {@code deliverReply/talkWait} 内部状态的做法收成一个端口；wait=true 的阻塞语义
 * 由 engine 的 {@code TalkService} 用 {@link WaitCoordinator} 实现。
 */
public interface TeamChannel {

    List<RoleSpec> roster();

    Optional<RoleSpec> findByName(String personName);

    /** 组内即时消息（不等回复）。 */
    void send(TalkMessage message);

    /**
     * 委派并同步等待回复（talk wait=true）。
     *
     * @param delegated 随消息投递给对方的任务；null 表示只发消息
     * @return 对方回复；超时或对方下班解阻塞时返回提示文本
     */
    Optional<String> sendAndWait(TalkMessage message, Task delegated, Duration timeout);

    record TalkMessage(RoleId from, String fromName, RoleId to, String toName,
                       String group, String text, String urgency) {
    }
}
