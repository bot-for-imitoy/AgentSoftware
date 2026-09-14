package com.agent.software.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitCoordinatorTest {

    @Test
    void deliverWakesWaitingThread() throws Exception {
        WaitCoordinator waits = new WaitCoordinator();
        assertTrue(waits.begin("architect"));
        assertTrue(waits.isWaiting());

        AtomicReference<Optional<String>> got = new AtomicReference<>();
        Thread t = new Thread(() -> got.set(waits.await(Duration.ofSeconds(2))));
        t.start();
        Thread.sleep(50);
        assertTrue(waits.deliver("here is the answer"));
        t.join(2000);

        assertEquals("here is the answer", got.get().orElseThrow());
        assertEquals("architect", waits.waitingFor());
    }

    @Test
    void abortInjectsSyntheticReply() {
        WaitCoordinator waits = new WaitCoordinator();
        waits.begin("ceo");
        assertTrue(waits.abort("shift ended"));
        assertEquals("shift ended", waits.await(Duration.ofMillis(10)).orElseThrow());
    }

    @Test
    void deliverWithoutWaiterIsRejected() {
        assertFalse(new WaitCoordinator().deliver("nobody"));
    }

    @Test
    void endClearsStaleReplySoNextWaitStartsFresh() {
        WaitCoordinator waits = new WaitCoordinator();
        waits.begin("x");
        waits.deliver("stale");
        waits.end();
        assertFalse(waits.isWaiting());
        assertTrue(waits.begin("y"));
        assertTrue(waits.await(Duration.ofMillis(20)).isEmpty(), "stale reply leaked into the next wait");
    }
}
