package com.agent.software.runtime;

import com.agent.software.domain.Payload;
import com.agent.software.domain.Task;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskQueueTest {

    private static Task task(int urgency, String description) {
        return Task.create(urgency, description, "test", Payload.empty());
    }

    @Test
    void popsHighestUrgencyFirst() {
        TaskQueue q = new TaskQueue();
        q.push(task(3, "normal"));
        q.push(task(10, "emergency"));
        q.push(task(1, "low"));
        assertEquals("emergency", q.pop().orElseThrow().description());
        assertEquals("normal", q.pop().orElseThrow().description());
        assertEquals("low", q.pop().orElseThrow().description());
        assertTrue(q.pop().isEmpty());
    }

    @Test
    void tiesAreServedFifo() {
        TaskQueue q = new TaskQueue();
        q.push(task(3, "first"));
        q.push(task(3, "second"));
        q.push(task(3, "third"));
        assertEquals(List.of("first", "second", "third"),
                q.snapshot().stream().map(Task::description).toList());
        assertEquals("first", q.pop().orElseThrow().description());
    }

    @Test
    void snapshotReflectsPopOrderWithoutDraining() {
        TaskQueue q = new TaskQueue();
        q.push(task(1, "low"));
        q.push(task(6, "high"));
        assertEquals(List.of("high", "low"), q.snapshot().stream().map(Task::description).toList());
        assertEquals(2, q.size());
    }

    @Test
    void peekDoesNotRemove() {
        TaskQueue q = new TaskQueue();
        q.push(task(3, "only"));
        Optional<Task> peeked = q.peek();
        assertTrue(peeked.isPresent());
        assertEquals(1, q.size());
    }
}
