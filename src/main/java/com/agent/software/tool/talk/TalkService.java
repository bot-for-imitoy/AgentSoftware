package com.agent.software.tool.talk;

import com.agent.software.agent.Agent;
import com.agent.software.agent.Team;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.agent.task.Task;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;
import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.EventKind;
import com.agent.software.sim.event.Priority;
import com.agent.software.transcript.Transcript;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import com.agent.software.tool.talk.TeamChannel.TalkMessage;

/**
 * {@link TeamChannel} 的 engine 实现：talk 工具的运行时后端。
 *
 * <p>wait=true 的语义在这里：用发送者的 {@link WaitCoordinator} 进入等待，
 * 把委派任务投给目标角色，收到回复或超时后结束等待。master 中这套逻辑直接写在
 * {@code TalkTo} + {@code AgentRole.talkWait} 里，工具因此穿透到了角色内部。
 *
 * <p>回复通路是一条进程内接缝：任务挂上 {@link Task#onComplete} 回调，执行方
 * {@code Agent#finishTask} 结束时调用 {@code notifyComplete}，把结果投回发送者的
 * {@link com.agent.software.agent.WaitCoordinator}。不需要任何跨线程的显式信箱。
 */
public final class TalkService implements TeamChannel {

    private static final Logger logger = LoggerFactory.getLogger(TalkService.class);

    /** 普通消息（不等回复）。 */
    private static final EventKind TALK_MESSAGE = new EventKind("talk", "MESSAGE");

    /** 需要回复的委派（wait=true）。 */
    private static final EventKind TALK_DELEGATE = new EventKind("talk", "DELEGATE");

    private final Team team;
    private final Transcript transcript;

    public TalkService(Team team, Transcript transcript) {
        this.team = team;
        this.transcript = transcript;
    }

    @Override
    public List<RoleSpec> roster() {
        return List.copyOf(team.specs());
    }

    @Override
    public Optional<RoleSpec> findByName(String personName) {
        if (Text.isBlank(personName)) {
            return Optional.empty();
        }
        String wanted = personName.trim();
        for (RoleSpec spec : team.specs()) {
            if (wanted.equals(spec.name())) {
                return Optional.of(spec);
            }
        }
        for (RoleSpec spec : team.specs()) {
            if (!Text.isBlank(spec.username()) && wanted.equalsIgnoreCase(spec.username().trim())) {
                return Optional.of(spec);
            }
        }
        return Optional.empty();
    }

    @Override
    public void send(TalkMessage message) {
        if (message == null || message.to() == null) {
            logger.warn("talk 消息为空或缺少收件人，已忽略");
            return;
        }
        Optional<Agent> target = team.agent(message.to());
        if (target.isEmpty()) {
            logger.warn("talk 目标角色不存在，消息未投递: {}", message.to().value());
            return;
        }
        record(message);
        target.get().submit(task(message, TALK_MESSAGE), false);
    }

    /**
     * 委派并同步等待回复。缺发送方或接收方时返回 {@link Optional#empty()}（由工具层翻译成中文提示），
     * 不抛异常——target 可能刚离职，这是正常竞态。
     */
    @Override
    public Optional<String> sendAndWait(TalkMessage message, Task delegated, Duration timeout) {
        if (message == null || message.from() == null || message.to() == null) {
            return Optional.empty();
        }
        Optional<Agent> from = team.agent(message.from());
        Optional<Agent> to = team.agent(message.to());
        if (from.isEmpty() || to.isEmpty()) {
            logger.warn("talk wait=true 失败：发送方或接收方不存在（from={}, to={}）",
                    message.from().value(), message.to().value());
            return Optional.empty();
        }
        record(message);

        Agent sender = from.get();
        sender.waits().begin(message.to());
        try {
            Task task = delegated != null ? delegated : task(message, TALK_DELEGATE);
            // 回复接缝：任务执行方 finishTask → notifyComplete(result) → 唤醒发送方。
            task.onComplete(sender.waits()::deliver);
            to.get().submit(task, false);
            return sender.waits().await(timeout);
        } finally {
            sender.waits().end();
        }
    }

    // ── 内部 ───────────────────────────────────────────────────

    /** 由 talk 消息构造投递任务；payload 至少携带 text 与 from，便于对方理解上下文。 */
    private Task task(TalkMessage message, EventKind kind) {
        Priority urgency = Priority.parse(message.urgency());
        boolean waiting = kind == TALK_DELEGATE;
        Payload payload = Payload.empty()
                .with("text", Text.orEmpty(message.text()))
                .with("from", message.from() == null ? "" : message.from().value())
                .with("fromName", Text.orEmpty(message.fromName()))
                .with("group", Text.orEmpty(message.group()))
                .with("urgency", urgency.name())
                .with("waiting", waiting)
                .with("title", "[talk] " + Text.truncate(Text.squashWhitespace(message.text()), 80));
        AgentEvent event = AgentEvent.toRole(message.to(), kind, urgency, payload);
        return Task.fromEvent(event, message.to());
    }

    /** 群聊轨迹写入失败不影响消息投递。 */
    private void record(TalkMessage message) {
        if (transcript == null || message == null) {
            return;
        }
        try {
            transcript.talk(new Transcript.Talk(message.from(), message.fromName(),
                    message.to(), message.toName(), Text.orEmpty(message.group()),
                    Text.orEmpty(message.text()), Text.orEmpty(message.urgency())));
        } catch (RuntimeException e) {
            logger.warn("写入 talk 轨迹失败: {}", e.getMessage());
        }
    }
}
