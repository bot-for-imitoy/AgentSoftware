package com.agent.software.tools.builtin;

import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.InputPort;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.Toolkit;
import com.agent.software.tools.spi.ToolService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientToolkitTest {

    private static final RoleId CEO = RoleId.of("CEO");
    private static final RoleId COO = RoleId.of("COO");

    static final class FakeInput implements InputPort {
        final AtomicReference<String> lastQuestion = new AtomicReference<>("");
        volatile String reply = "a payment system";
        volatile boolean available = true;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        public ClientReply ask(ClientQuestion question, Duration timeout) {
            lastQuestion.set(question.text());
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return available ? ClientReply.answered(reply) : ClientReply.unavailable("no browser attached");
        }
    }

    private static RoleSpec spec(RoleId id, String name) {
        return new RoleSpec(id, name, "user", 1101, "Title", "", "", List.of(), "",
                "Leadership Group", "", "local", Payload.empty(), List.of("client"));
    }

    private static Function<RoleId, Optional<RoleSpec>> roles() {
        return id -> id.equals(CEO) ? Optional.of(spec(CEO, "Lin Zong"))
                : id.equals(COO) ? Optional.of(spec(COO, "Chen Zong")) : Optional.empty();
    }

    private static ToolResult call(ToolService service, RoleId role, String message) {
        return service.invoke(role, new ToolCall("c", "talk_to_client", Payload.of(Map.of("message", message))));
    }

    @Test
    void returnsTheClientReplyAndRecordsTheQuestion() {
        FakeInput input = new FakeInput();
        AtomicReference<ClientToolkit.ClientRecord> recorded = new AtomicReference<>();
        Toolkit toolkit = ClientToolkit.create(input, roles(), recorded::set, Duration.ofSeconds(1));
        ToolService service = new ToolService();
        service.bind(CEO, List.of(toolkit));

        ToolResult result = call(service, CEO, "What should we build?");
        assertTrue(result.ok(), result.text());
        assertTrue(result.text().contains("a payment system"));
        assertEquals("What should we build?", input.lastQuestion.get());
        assertNotNull(recorded.get());
        assertEquals(CEO, recorded.get().role());
    }

    @Test
    void unavailableChannelBecomesAnError() {
        FakeInput input = new FakeInput();
        input.available = false;
        ToolService service = new ToolService();
        service.bind(CEO, List.of(ClientToolkit.create(input, roles(), r -> {
        }, Duration.ofSeconds(1))));

        ToolResult result = call(service, CEO, "hello?");
        assertFalse(result.ok());
        assertTrue(result.text().contains("no browser"));
    }

    @Test
    void onlyOneMemberCanTalkToTheClientAtATime() throws Exception {
        FakeInput input = new FakeInput();
        input.entered = new CountDownLatch(1);
        input.release = new CountDownLatch(1);
        Toolkit toolkit = ClientToolkit.create(input, roles(), r -> {
        }, Duration.ofSeconds(2));
        ToolService service = new ToolService();
        service.bind(CEO, List.of(toolkit));
        service.bind(COO, List.of(toolkit));

        Thread first = new Thread(() -> call(service, CEO, "I am asking"));
        first.start();
        assertTrue(input.entered.await(2, TimeUnit.SECONDS));

        ToolResult conflict = call(service, COO, "me too");
        assertFalse(conflict.ok());
        assertTrue(conflict.text().contains("already talking"));

        input.release.countDown();
        first.join(2000);

        input.entered = null;
        input.release = null;
        input.reply = "second answer";
        assertTrue(call(service, COO, "now it is my turn").ok(), "lock must be released");
    }
}
