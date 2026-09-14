package com.agent.software.domain;

import com.agent.software.kernel.TaskId;

import java.time.Instant;
import java.util.Objects;

/**
 * A unit of work queued on exactly one role.
 *
 * <p>Immutable identity/description plus a small mutable execution result
 * (status, result text, token cost). The mutable part is guarded so a Web/trace
 * reader never observes a torn value.
 */
public final class Task {

    private final TaskId id;
    private final int urgency;
    private final String description;
    private final String source;
    private final Payload context;
    private final Instant createdAt;

    private volatile TaskStatus status = TaskStatus.PENDING;
    private volatile String result = "";
    private volatile int tokensConsumed = 0;
    private volatile RoleIdRef assignedRole;

    public Task(TaskId id, int urgency, String description, String source, Payload context, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.urgency = Math.max(1, urgency);
        this.description = description == null ? "" : description;
        this.source = source == null ? "" : source;
        this.context = context == null ? Payload.empty() : context;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static Task create(int urgency, String description, String source, Payload context) {
        return new Task(TaskId.newId(), urgency, description, source, context, Instant.now());
    }

    public TaskId id() {
        return id;
    }

    public int urgency() {
        return urgency;
    }

    public String description() {
        return description;
    }

    public String source() {
        return source;
    }

    public Payload context() {
        return context;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public TaskStatus status() {
        return status;
    }

    public String result() {
        return result;
    }

    public int tokensConsumed() {
        return tokensConsumed;
    }

    public String assignedRoleId() {
        RoleIdRef ref = assignedRole;
        return ref == null ? "" : ref.value();
    }

    public synchronized void assignTo(String roleId) {
        this.assignedRole = roleId == null ? null : new RoleIdRef(roleId);
    }

    public synchronized void markRunning() {
        this.status = TaskStatus.RUNNING;
    }

    public synchronized void markDone(String result, int tokensConsumed) {
        this.status = TaskStatus.DONE;
        this.result = result == null ? "" : result;
        this.tokensConsumed = Math.max(0, tokensConsumed);
    }

    public synchronized void markFailed(String error, int tokensConsumed) {
        this.status = TaskStatus.FAILED;
        this.result = error == null ? "" : error;
        this.tokensConsumed = Math.max(0, tokensConsumed);
    }

    @Override
    public String toString() {
        return "Task(" + id + ", urgency=" + urgency + ", " + status.wireName() + ")";
    }

    /** Small holder to keep the assigned-role write atomic without importing RoleId here. */
    private record RoleIdRef(String value) {
    }
}
