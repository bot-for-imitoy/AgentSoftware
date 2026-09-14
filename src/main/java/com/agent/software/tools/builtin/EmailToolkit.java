package com.agent.software.tools.builtin;

import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.MailPort;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.Toolkit;
import com.agent.software.tools.spi.Tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The {@code email} toolkit: company mail over {@link MailPort}.
 *
 * <p>Recipients may be a full address or a colleague name/role id resolved
 * against the team roster.
 */
public final class EmailToolkit {

    private EmailToolkit() {
    }

    public static Toolkit create(MailPort mail,
                                 Function<RoleId, Optional<RoleSpec>> roleLookup,
                                 Supplier<List<RoleSpec>> roster) {
        Tool send = Tools.of("send_email", "Send a company email",
                JsonSchema.builder()
                        .required("to", JsonSchema.Property.string(
                                "Recipient: colleague name or email, comma separated for multiple."))
                        .required("subject", JsonSchema.Property.string("Email subject (one sentence)."))
                        .required("body", JsonSchema.Property.string("Email body."))
                        .property("cc", JsonSchema.Property.string("(Optional) CC: name or email, comma separated."))
                        .build(),
                (role, call) -> {
                    Optional<RoleSpec> sender = roleLookup.apply(role);
                    if (sender.isEmpty()) {
                        return ToolResult.error("send_email: Error: unknown sender role " + role);
                    }
                    String to = Tools.argStripped(call, "to");
                    String subject = Tools.argStripped(call, "subject");
                    String body = Tools.arg(call, "body");
                    if (to.isEmpty()) {
                        return ToolResult.error("send_email: Error: needs 'to' (recipient)");
                    }
                    if (subject.isEmpty() && body.isBlank()) {
                        return ToolResult.error("send_email: Error: at least one of 'subject' or 'body' is required");
                    }

                    List<RoleSpec> rosterList = roster.get();
                    List<String> unresolved = new ArrayList<>();
                    List<String> recipients = resolve(to, rosterList, unresolved, mail);
                    List<String> cc = resolve(Tools.argStripped(call, "cc"), rosterList, unresolved, mail);
                    if (recipients.isEmpty()) {
                        return ToolResult.error("send_email: Error: could not resolve recipients: "
                                + (unresolved.isEmpty() ? "(empty)" : String.join(", ", unresolved)));
                    }
                    String summary = mail.send(new MailPort.MailDraft(
                            mail.addressFor(sender.get()), sender.get().name(),
                            recipients, subject, body, cc));
                    String note = unresolved.isEmpty() ? "" : " (unresolved: " + String.join(", ", unresolved) + ")";
                    return ToolResult.success("send_email: " + summary + note);
                });

        Tool read = Tools.of("read_mail", "List the messages in your inbox",
                JsonSchema.builder()
                        .property("limit", JsonSchema.Property.integer("(Optional) Max messages (default 10)."))
                        .property("unread_only", JsonSchema.Property.bool("(Optional) Only unread mail."))
                        .build(),
                (role, call) -> {
                    Optional<RoleSpec> sender = roleLookup.apply(role);
                    if (sender.isEmpty()) {
                        return ToolResult.error("read_mail: Error: unknown role " + role);
                    }
                    String address = mail.addressFor(sender.get());
                    int limit = Tools.intArg(call, "limit", 10);
                    boolean unreadOnly = call.arguments().boolVal("unread_only", false);
                    List<MailPort.MailMessage> inbox = mail.inbox(address, Math.max(0, limit), unreadOnly);
                    if (inbox.isEmpty()) {
                        return ToolResult.success("read_mail: inbox is empty (" + address + ")");
                    }
                    StringBuilder sb = new StringBuilder("read_mail: inbox " + address + ":");
                    for (MailPort.MailMessage m : inbox) {
                        sb.append("\n- [").append(m.id()).append("] ").append(m.senderName())
                                .append(": ").append(m.subject())
                                .append(m.read() ? "" : " (unread)");
                    }
                    return ToolResult.success(sb.toString());
                });

        Tool open = Tools.of("open_mail", "Open one message by id (marks it read)",
                JsonSchema.builder()
                        .required("message_id", JsonSchema.Property.string("The mail id from read_mail."))
                        .build(),
                (role, call) -> {
                    Optional<RoleSpec> sender = roleLookup.apply(role);
                    if (sender.isEmpty()) {
                        return ToolResult.error("open_mail: Error: unknown role " + role);
                    }
                    String id = Tools.argStripped(call, "message_id");
                    if (id.isEmpty()) {
                        return ToolResult.error("open_mail: Error: needs message_id");
                    }
                    Optional<MailPort.MailMessage> message = mail.open(mail.addressFor(sender.get()), id);
                    if (message.isEmpty()) {
                        return ToolResult.error("open_mail: Error: mail not found: " + id);
                    }
                    MailPort.MailMessage m = message.get();
                    return ToolResult.success("From: " + m.senderName() + " <" + m.senderEmail() + ">\n"
                            + "To: " + String.join(", ", m.recipients()) + "\n"
                            + "Subject: " + m.subject() + "\n\n" + m.body());
                });

        Tool addressBook = Tools.of("mail_address_book", "List company email addresses",
                JsonSchema.builder()
                        .property("group", JsonSchema.Property.string(
                                "(Optional) Only show one group, e.g. 'Testing Group'."))
                        .build(),
                (role, call) -> {
                    String filter = Tools.argStripped(call, "group");
                    List<RoleSpec> all = roster.get();
                    Map<String, List<RoleSpec>> byGroup = new LinkedHashMap<>();
                    for (RoleSpec r : all) {
                        String group = r.hasGroup() ? r.group() : "Ungrouped";
                        byGroup.computeIfAbsent(group, k -> new ArrayList<>()).add(r);
                    }
                    StringBuilder sb = new StringBuilder("mail_address_book ("
                            + all.size() + " people):");
                    List<String> groups = new ArrayList<>(byGroup.keySet());
                    groups.sort(String::compareTo);
                    for (String group : groups) {
                        if (!filter.isEmpty() && !filter.equalsIgnoreCase(group)) {
                            continue;
                        }
                        sb.append("\n[").append(group).append("]");
                        for (RoleSpec r : byGroup.get(group)) {
                            sb.append("\n- ").append(r.name()).append(" ").append(mail.addressFor(r))
                                    .append(" \u2014 ").append(r.title().isEmpty() ? r.id().value() : r.title());
                        }
                    }
                    return ToolResult.success(sb.toString());
                });

        return new Toolkit("email", "Company email toolkit: send, read, open mail and look up addresses",
                List.of(send, read, open, addressBook));
    }

    private static List<String> resolve(String raw, List<RoleSpec> roster,
                                        List<String> unresolved, MailPort mail) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String value = part.strip();
            if (value.isEmpty()) {
                continue;
            }
            if (value.contains("@")) {
                out.add(value);
                continue;
            }
            Optional<RoleSpec> match = roster.stream()
                    .filter(r -> r.name().equalsIgnoreCase(value) || r.id().value().equalsIgnoreCase(value))
                    .findFirst();
            if (match.isPresent()) {
                out.add(mail.addressFor(match.get()));
            } else {
                unresolved.add(value);
            }
        }
        return out;
    }
}
