package com.agent.software.runtime;

import com.agent.software.domain.DeliveryMode;
import com.agent.software.domain.DeliveryPolicy;
import com.agent.software.domain.Event;
import com.agent.software.domain.Payload;
import com.agent.software.domain.Priority;
import com.agent.software.domain.Task;
import com.agent.software.kernel.RoleId;
import com.agent.software.kernel.TaskId;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Routes an event to the roles that should receive it.
 *
 * <p>There is no content filter here: recipients come from the event itself
 * (empty = broadcast). The only decision left is the lifecycle
 * {@link DeliveryPolicy}, reported per recipient.
 */
public final class DispatchService {

    /** What happened to one recipient. */
    public record DeliveryOutcome(TaskId task, DeliveryMode mode, String reason) {
    }

    private final TeamRuntime team;
    private final Function<Event, Task> taskFactory;

    public DispatchService(TeamRuntime team) {
        this(team, DispatchService::toTask);
    }

    public DispatchService(TeamRuntime team, Function<Event, Task> taskFactory) {
        this.team = team;
        this.taskFactory = taskFactory == null ? DispatchService::toTask : taskFactory;
    }

    public Map<RoleId, DeliveryOutcome> dispatch(Event event) {
        Map<RoleId, DeliveryOutcome> outcomes = new LinkedHashMap<>();
        Set<RoleId> targets = new LinkedHashSet<>();
        if (event.isBroadcast()) {
            targets.addAll(team.ids());
        } else {
            targets.addAll(event.recipients());
        }
        for (RoleId id : targets) {
            var runtime = team.find(id);
            if (runtime.isEmpty()) {
                outcomes.put(id, new DeliveryOutcome(null, DeliveryMode.IMMEDIATE, "no such role"));
                continue;
            }
            DeliveryPolicy policy = DeliveryPolicy.forState(event.priority(), runtime.get().state());
            Task task = taskFactory.apply(event);
            runtime.get().submit(task);
            outcomes.put(id, new DeliveryOutcome(task.id(), policy.mode(), policy.reason()));
        }
        return outcomes;
    }

    /** Default event → task mapping. */
    public static Task toTask(Event event) {
        int urgency = switch (event.priority()) {
            case LOW -> Priority.LOW.value();
            case HIGH -> Priority.HIGH.value();
            case EMERGENCY -> Priority.EMERGENCY.value();
            default -> Priority.NORMAL.value();
        };
        Payload context = Payload.of("event_id", event.id().value()).mergedWith(event.payload());
        String title = event.payload().str("title", "");
        String detail = title.isEmpty()
                ? truncate(String.valueOf(event.payload().asMap()), 100)
                : title;
        return Task.create(urgency, "[" + event.source() + "/" + event.type() + "] " + detail,
                event.source(), context);
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }
}
