package com.agent.software.ports;

import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for a whole application snapshot.
 *
 * <p>Deliberately dumb: it stores plain data and never rebuilds runtime objects,
 * so the composition root stays in charge of reconstructing state.
 */
public interface StateRepository {

    Optional<Snapshot> load();

    void save(Snapshot snapshot);

    /** Application-level state. */
    record Snapshot(int day, int tickOfDay, String baseDate, List<RoleState> roles) {
        public Snapshot {
            baseDate = baseDate == null ? "" : baseDate;
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    /** One role's definition plus runtime state. */
    record RoleState(RoleSpec spec, String state, List<TaskSnapshot> pending, List<TaskSnapshot> history) {
        public RoleState {
            state = state == null ? "IDLE" : state;
            pending = pending == null ? List.of() : List.copyOf(pending);
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    /** One task. */
    record TaskSnapshot(String id, int urgency, String description, String source, Payload context,
                        String status, String result, int tokens, double createdAt) {
        public TaskSnapshot {
            id = id == null ? "" : id;
            description = description == null ? "" : description;
            source = source == null ? "" : source;
            context = context == null ? Payload.empty() : context;
            status = status == null ? "pending" : status;
            result = result == null ? "" : result;
        }
    }
}
