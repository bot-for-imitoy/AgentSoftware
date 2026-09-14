package com.agent.software.ports;

import com.agent.software.domain.Event;

/**
 * Receives every event the runtime produces or schedules.
 *
 * <p>The clock, mail gateway and tools all publish through this single sink; the
 * composition root wires it to the dispatch service.
 */
@FunctionalInterface
public interface EventSink {

    void publish(Event event);
}
