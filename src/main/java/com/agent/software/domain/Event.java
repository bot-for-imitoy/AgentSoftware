package com.agent.software.domain;

import com.agent.software.kernel.EventId;
import com.agent.software.kernel.RoleId;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * An immutable event.
 *
 * <p>Per refactor plan v2 there is <b>no content-based filtering</b>. The
 * producer states the recipients explicitly; an empty set means "broadcast to
 * every role". {@link DeliveryPolicy} then only decides whether delivery is
 * immediate or held because of the recipient's lifecycle state.
 */
public record Event(
        EventId id,
        String source,
        EventType type,
        Priority priority,
        Payload payload,
        Instant at,
        Set<RoleId> recipients,
        OptionalInt triggerTick) {

    public Event {
        Objects.requireNonNull(id, "id");
        source = source == null ? "" : source;
        Objects.requireNonNull(type, "type");
        priority = priority == null ? Priority.NORMAL : priority;
        payload = payload == null ? Payload.empty() : payload;
        at = at == null ? Instant.now() : at;
        recipients = recipients == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(recipients));
        triggerTick = triggerTick == null ? OptionalInt.empty() : triggerTick;
    }

    /** An immediate broadcast event. */
    public static Event broadcast(String source, EventType type, Priority priority, Payload payload) {
        return new Event(EventId.newId(), source, type, priority, payload, Instant.now(), Set.of(), OptionalInt.empty());
    }

    /** An immediate event delivered only to {@code recipients}. */
    public static Event targeted(String source, EventType type, Priority priority,
                                 Payload payload, Set<RoleId> recipients) {
        return new Event(EventId.newId(), source, type, priority, payload, Instant.now(), recipients, OptionalInt.empty());
    }

    /** An immediate event delivered only to one role. */
    public static Event toRole(String source, EventType type, Priority priority,
                               Payload payload, RoleId recipient) {
        return targeted(source, type, priority, payload, Set.of(recipient));
    }

    /** A copy scheduled to fire at an absolute tick. */
    public Event scheduledAt(int absoluteTick) {
        return new Event(id, source, type, priority, payload, at, recipients, OptionalInt.of(absoluteTick));
    }

    /** True when no explicit recipient was given. */
    public boolean isBroadcast() {
        return recipients.isEmpty();
    }
}
