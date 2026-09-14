package com.agent.software.runtime;

import com.agent.software.domain.Task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Thread-safe priority queue for one role.
 *
 * <p>Ordering is by descending urgency, ties broken by insertion order (FIFO).
 * The insertion counter is per-queue, not a process-global static as in the
 * legacy implementation.
 */
public final class TaskQueue {

    private record Entry(Task task, long seq) {
    }

    private static final Comparator<Entry> ORDER =
            Comparator.comparingInt((Entry e) -> -e.task().urgency()).thenComparingLong(Entry::seq);

    private final PriorityQueue<Entry> queue = new PriorityQueue<>(ORDER);
    private final Object lock = new Object();
    private long seq = 0;

    public void push(Task task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        synchronized (lock) {
            queue.add(new Entry(task, ++seq));
        }
    }

    public Optional<Task> pop() {
        synchronized (lock) {
            Entry e = queue.poll();
            return e == null ? Optional.empty() : Optional.of(e.task());
        }
    }

    public Optional<Task> peek() {
        synchronized (lock) {
            Entry e = queue.peek();
            return e == null ? Optional.empty() : Optional.of(e.task());
        }
    }

    public int size() {
        synchronized (lock) {
            return queue.size();
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** Tasks in the order they would be popped. */
    public List<Task> snapshot() {
        synchronized (lock) {
            List<Entry> sorted = new ArrayList<>(queue);
            sorted.sort(ORDER);
            List<Task> out = new ArrayList<>(sorted.size());
            for (Entry e : sorted) {
                out.add(e.task());
            }
            return out;
        }
    }
}
