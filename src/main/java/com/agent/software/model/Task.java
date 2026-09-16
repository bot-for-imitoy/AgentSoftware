package com.agent.software.model;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TaskId;

import java.time.Instant;

/**
 * 一个待执行任务。
 *
 * <p>定义部分不可变（id/紧急度/描述/来源/上下文/创建时刻/负责人）；执行结果只能通过
 * {@link #markRunning()} / {@link #complete} / {@link #fail} 显式迁移写入，
 * 唯一写者是执行它的 {@code engine.Agent}。
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

    public Task(TaskId id, Priority urgency, String description, EventKind source,
                Payload context, Instant createdAt, RoleId assignee) {
        this.id = id;
        this.urgency = urgency;
        this.description = description;
        this.source = source;
        this.context = context;
        this.createdAt = createdAt;
        this.assignee = assignee;
    }

    /** 由通过投递决策的事件生成任务。 */
    public static Task fromEvent(AgentEvent event, RoleId assignee) {
        throw new UnsupportedOperationException("skeleton");
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
    public void markRunning() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** RUNNING → DONE，并记录结果与 token 消耗。 */
    public void complete(String result, int tokens) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** RUNNING → FAILED。 */
    public void fail(String reason) {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean isOpen() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 转为持久化记录（含执行结果）。 */
    public TaskRecord toRecord() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 从持久化记录还原。 */
    public static Task fromRecord(TaskRecord record) {
        throw new UnsupportedOperationException("skeleton");
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
