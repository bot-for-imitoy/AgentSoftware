package com.agent.software.adapters.input;

import com.agent.software.ports.InputPort;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Console implementation of {@link InputPort}.
 *
 * <p>Replaces {@code StdInput}. The timeout is advisory for a console: a real
 * terminal blocks until the user types a line, so the duration is ignored.
 */
public final class ConsoleInputAdapter implements InputPort {

    private final BufferedReader reader;
    private final PrintStream out;

    public ConsoleInputAdapter() {
        this(System.in, System.out);
    }

    public ConsoleInputAdapter(InputStream in, PrintStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    public ClientReply ask(ClientQuestion question, Duration timeout) {
        out.println();
        out.println("  [" + (question.askerName().isEmpty() ? "agent" : question.askerName()) + "] "
                + (question.text().isEmpty() ? "(sent a message, please reply)" : question.text()));
        out.print("  [Client] reply: ");
        out.flush();
        try {
            String line = reader.readLine();
            if (line == null) {
                return ClientReply.unavailable("cannot get user input (non-interactive environment)");
            }
            return ClientReply.answered(line.strip());
        } catch (IOException e) {
            return ClientReply.unavailable("cannot get user input: " + e.getMessage());
        }
    }
}
