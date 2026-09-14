package com.agent.software.tools.builtin;

import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.InputPort;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.Toolkit;
import com.agent.software.tools.spi.Tools;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The {@code client} toolkit: talk to the human client through the configured
 * {@link InputPort}.
 *
 * <p>Only one member may hold the client conversation at a time; the lock lives in
 * the toolkit instance, which the composition root creates once per application.
 */
public final class ClientToolkit {

    /** One client-channel message for the chat feed. */
    public record ClientRecord(RoleId role, String name, String group, String text) {
    }

    private ClientToolkit() {
    }

    public static Toolkit create(InputPort input,
                                 Function<RoleId, Optional<RoleSpec>> roles,
                                 Consumer<ClientRecord> recorder,
                                 Duration timeout) {
        AtomicReference<String> holder = new AtomicReference<>();

        Tool talkToClient = Tools.of("talk_to_client",
                "Talk to the client (user): ask a question or report progress",
                JsonSchema.builder()
                        .property("message", JsonSchema.Property.string(
                                "(Optional) What you want to say to the client."))
                        .build(),
                (role, call) -> {
                    String current = holder.get();
                    if (current != null && !current.equals(role.value())) {
                        return ToolResult.error("talk_to_client: Error: another member (" + current
                                + ") is already talking to the client, try again later.");
                    }
                    holder.set(role.value());
                    try {
                        Optional<RoleSpec> spec = roles == null ? Optional.empty() : roles.apply(role);
                        String name = spec.map(RoleSpec::name).orElse(role.value());
                        String group = spec.map(RoleSpec::group).orElse("");
                        String question = Tools.arg(call, "message").strip();
                        if (recorder != null) {
                            recorder.accept(new ClientRecord(role, name, group, question));
                        }
                        InputPort.ClientReply reply = input.ask(
                                new InputPort.ClientQuestion(role, name, group, question), timeout);
                        if (!reply.answered()) {
                            return ToolResult.error("talk_to_client: Error: "
                                    + (reply.error().isBlank() ? "the client did not reply" : reply.error()));
                        }
                        String text = reply.text().strip();
                        if (text.isEmpty()) {
                            return ToolResult.success("talk_to_client: the client did not enter anything.");
                        }
                        return ToolResult.success("talk_to_client: client reply: " + text);
                    } finally {
                        holder.compareAndSet(role.value(), null);
                    }
                });

        return new Toolkit("client",
                "Client communication toolkit: talk to the client (user)",
                List.of(talkToClient));
    }
}
