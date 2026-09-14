package com.agent.software.ports;

import com.agent.software.domain.AgentState;
import com.agent.software.domain.RoleSpec;
import com.agent.software.domain.Task;
import com.agent.software.kernel.RoleId;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Team-wide operations tools need: roster lookup, task submission and the
 * synchronous talk exchange.
 *
 * <p>Keeping this a port means collaboration tools never import the runtime.
 */
public interface TeamPort {

    List<RoleSpec> members();

    Optional<RoleSpec> spec(RoleId id);

    /** Resolve a colleague by role id or person name. */
    Optional<RoleId> resolve(String nameOrId);

    AgentState stateOf(RoleId id);

    /** Whose reply a waiting role is blocked on, if any. */
    Optional<String> waitingReplyFrom(RoleId id);

    /** Deliver a talk reply to {@code target} if it is waiting for {@code from}. */
    boolean deliverReply(RoleId target, RoleId from, String message);

    void enqueue(RoleId target, Task task);

    /** Deliver {@code task} to {@code target}, then block {@code self} until it replies. */
    Optional<String> waitForReply(RoleId self, RoleId target, Task task, Duration timeout);

    void setState(RoleId id, AgentState state);

    List<Task> pendingTasks(RoleId id);

    List<Task> taskHistory(RoleId id, int limit);
}
