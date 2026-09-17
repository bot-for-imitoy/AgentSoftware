package com.agent.software.agent.task;

import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TaskId;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;
import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.EventKind;
import com.agent.software.sim.event.Priority;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/**
 * 一个待执行任务。
 *
 * <p>定义部分不可变（id/紧急度/描述/来源/上下文/创建时刻/负责人）；执行结果只能通过
 * {@link #markRunning()} / {@link #complete} / {@link #fail} 显式迁移写入，
 * 唯一写者是执行它的 {@code agent.Agent}。
 *
 * <p>{@link TaskRecord} 是它唯一的持久化形状，避免 master 那种 {@code Task} ⇄
 * {@code TaskSnapshot} ⇄ {@code Map} 的两跳 DTO 链。
 */
public final class Task {

    private final TaskId id;
    private final Priority urgency;
    private final String description;
    private final EventKind source;
    private final Payload context;
    private final Instant createdAt;
    private final RoleId assignee;

    private TaskStatus status = TaskStatus.PENDING;
    private String result = "";
    private int tokens = 0;

    /**
     * 完成回调（talk wait=true 的回复通道）。
     *
     * <p>它是一条**进程内**接缝：{@code tool.talk.TalkService} 在委派前挂上"把结果投回
     * 发送方的 {@code WaitCoordinator}"的动作，任务结束时由执行方
     * {@code agent.Agent#finishTask} 触发。因为它不是数据，所以**不进入**
     * {@link TaskRecord}，也不会被持久化。
     */
    private transient Consumer<String> replySink;

    public Task(TaskId id, Priority urgency, String description, EventKind source,
                Payload context, Instant createdAt, RoleId assignee) {
        this.id = id;
        this.urgency = urgency == null ? Priority.NORMAL : urgency;
        this.description = Text.orEmpty(description);
        this.source = source == null ? new EventKind("", "") : source;
        this.context = context == null ? Payload.empty() : context;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.assignee = assignee;
    }

    /** 由通过投递决策的事件生成任务。 */
    public static Task fromEvent(AgentEvent event, RoleId assignee) {
        Payload payload = event.payload();
        String description = firstNonBlank(payload.title(), payload.subject(), payload.text(),
                event.kind().wire());
        return new Task(TaskId.generate(), event.priority(), description, event.kind(),
                payload, Instant.now(), assignee);
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (!Text.isBlank(c)) {
                return c;
            }
        }
        return "";
    }

    public TaskId id() {
        return id;
    }

    public Priority urgency() {
        return urgency;
    }

    public String description() {
        return description;
    }

    public EventKind source() {
        return source;
    }

    public Payload context() {
        return context;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public RoleId assignee() {
        return assignee;
    }

    public TaskStatus status() {
        return status;
    }

    public String result() {
        return result;
    }

    public int tokens() {
        return tokens;
    }

    /** PENDING → RUNNING。 */
    public synchronized void markRunning() {
        if (status != TaskStatus.PENDING) {
            throw new DomainError("task.state.illegal",
                    "任务 " + id.value() + " 只能从 PENDING 进入 RUNNING，当前为 " + status);
        }
        status = TaskStatus.RUNNING;
    }

    /** RUNNING → DONE，并记录结果与 token 消耗。 */
    public synchronized void complete(String result, int tokens) {
        if (status.terminal()) {
            throw new DomainError("task.state.illegal",
                    "任务 " + id.value() + " 已结束（" + status + "），不能再次完成");
        }
        this.status = TaskStatus.DONE;
        this.result = Text.orEmpty(result);
        this.tokens = Math.max(0, tokens);
    }

    /** RUNNING → FAILED。 */
    public synchronized void fail(String reason) {
        if (status.terminal()) {
            throw new DomainError("task.state.illegal",
                    "任务 " + id.value() + " 已结束（" + status + "），不能再次置为失败");
        }
        this.status = TaskStatus.FAILED;
        this.result = Text.orEmpty(reason);
    }

    public boolean isOpen() {
        return !status.terminal();
    }

    /** 是否处于运行中。 */
    public boolean running() {
        return status == TaskStatus.RUNNING;
    }

    /** 是否为等待回复的委派任务（talk wait=true）。 */
    public boolean hasReplySink() {
        return replySink != null;
    }

    /** 挂上完成回调（仅 {@code tool.talk.TalkService} 使用）。 */
    public void onComplete(Consumer<String> sink) {
        this.replySink = sink;
    }

    /** 任务结束时把结果投回等待者（幂等：第一次触发后清空回调）。 */
    public void notifyComplete(String text) {
        Consumer<String> sink = this.replySink;
        this.replySink = null;
        if (sink != null) {
            sink.accept(Text.orEmpty(text));
        }
    }

    /** 转为持久化记录（含执行结果）。 */
    public TaskRecord toRecord() {
        return new TaskRecord(
                id.value(),
                urgency.weight(),
                description,
                source.wire(),
                context.asMap(),
                status.name(),
                result,
                tokens,
                createdAt.toEpochMilli() / 1000.0,
                assignee == null ? "" : assignee.value());
    }

    /** 从持久化记录还原。 */
    public static Task fromRecord(TaskRecord record) {
        if (record == null) {
            throw new DomainError("task.record.null", "任务记录为空");
        }
        Task task = new Task(
                new TaskId(Text.isBlank(record.id()) ? com.agent.software.kernel.Ids.TaskId.generate().value() : record.id()),
                Priority.ofWeight(record.urgency()),
                record.description(),
                EventKind.parse(record.source()),
                Payload.ofMap(record.context() == null ? new LinkedHashMap<>() : record.context()),
                Instant.ofEpochMilli((long) (record.createdAt() * 1000)),
                Text.isBlank(record.assignee()) ? null : new RoleId(record.assignee()));
        task.status = parseStatus(record.status());
        task.result = Text.orEmpty(record.result());
        task.tokens = record.tokens();
        return task;
    }

    private static TaskStatus parseStatus(String name) {
        if (name != null) {
            for (TaskStatus s : TaskStatus.values()) {
                if (s.name().equalsIgnoreCase(name.trim())) {
                    return s;
                }
            }
        }
        return TaskStatus.PENDING;
    }

    @Override
    public String toString() {
        return "Task(" + id.value() + ", " + urgency + ", " + status + ", " + Text.truncate(description, 40) + ")";
    }

    /** 任务在磁盘上的唯一形状。 */
    public record TaskRecord(
            String id,
            int urgency,
            String description,
            String source,
            java.util.Map<String, Object> context,
            String status,
            String result,
            int tokens,
            double createdAt,
            String assignee) {
    }
}
