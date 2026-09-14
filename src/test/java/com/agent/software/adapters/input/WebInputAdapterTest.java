package com.agent.software.adapters.input;

import com.agent.software.kernel.RoleId;
import com.agent.software.ports.InputPort;
import com.agent.software.adapters.web.ChatStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebInputAdapterTest {

    private static final RoleId CEO = RoleId.of("CEO");

    private static InputPort.ClientQuestion question() {
        return new InputPort.ClientQuestion(CEO, "Lin Zong", "Leadership Group", "What shall we build?");
    }

    private static boolean waitFor(BooleanSupplier condition, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    @Test
    void returnsTheReplyPostedOnThePage() throws Exception {
        ChatStore store = new ChatStore();
        store.markAttached();
        WebInputAdapter adapter = new WebInputAdapter(store);

        AtomicReference<InputPort.ClientReply> reply = new AtomicReference<>();
        Thread asker = new Thread(() -> reply.set(adapter.ask(question(), Duration.ofSeconds(3))));
        asker.start();

        assertTrue(waitFor(store::isClientWaitPending, 2000), "the question never became pending");
        assertTrue(store.postClientReply("a payment system") != null);
        asker.join(3000);

        assertTrue(reply.get().answered());
        assertEquals("a payment system", reply.get().text());
    }

    @Test
    void detachedPageIsReportedInsteadOfBlocking() {
        ChatStore store = new ChatStore();
        WebInputAdapter adapter = new WebInputAdapter(store);
        InputPort.ClientReply reply = adapter.ask(question(), Duration.ofSeconds(1));
        assertFalse(reply.answered());
        assertTrue(reply.error().contains("not attached"));
    }
}
