package com.agent.software.adapters.input;

import com.agent.software.kernel.RoleId;
import com.agent.software.ports.InputPort;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleInputAdapterTest {

    private static ConsoleInputAdapter adapter(String stdin) {
        return new ConsoleInputAdapter(
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    private static InputPort.ClientQuestion question() {
        return new InputPort.ClientQuestion(RoleId.of("CEO"), "Lin Zong", "Leadership Group", "What shall we build?");
    }

    @Test
    void returnsTheTypedLine() {
        InputPort.ClientReply reply = adapter("a payment system\n").ask(question(), Duration.ofSeconds(1));
        assertTrue(reply.answered());
        assertEquals("a payment system", reply.text());
    }

    @Test
    void eofIsReportedAsUnavailable() {
        InputPort.ClientReply reply = adapter("").ask(question(), Duration.ofSeconds(1));
        assertFalse(reply.answered());
        assertTrue(reply.error().contains("non-interactive"));
    }
}
