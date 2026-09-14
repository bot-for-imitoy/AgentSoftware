package com.agent.software.tools.builtin;

import com.agent.software.domain.AgentState;
import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.domain.Task;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ComputerPort;
import com.agent.software.ports.TeamPort;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.Toolkit;
import com.agent.software.tools.spi.Tools;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The {@code talk} toolkit: same-group chat and task delegation, plus the team roster.
 *
 * <p>Rules preserved from the legacy tool: same-group only (cross-group goes
 * through email), attachment must be a readable cloud-drive path, and
 * {@code wait=true} blocks on the {@link TeamPort} wait exchange with a
 * mutual-wait cycle check.
 */
public final class TalkToolkit {

    /** One recorded talk message for the chat feed. */
    public record TalkRecord(RoleId fromRole, String fromName, String group,
                             RoleId toRole, String toName, String text, String urgency) {
    }

    private TalkToolkit() {
    }

    public static Toolkit create(TeamPort team,
                                 Function<RoleId, Optional<ComputerPort>> computers,
                                 Consumer<TalkRecord> recorder) {
        Tool talk = Tools.of("talk", "Send a message or delegate a task to a same-group colleague",
                JsonSchema.builder()
                        .required("target", JsonSchema.Property.string(
                                "Target member name (see list_roles first)."))
                        .required("message", JsonSchema.Property.string("The message or delegated task."))
                        .property("urgency", JsonSchema.Property.stringEnum(
                                "(Optional) Urgency level.", "LOW", "NORMAL", "HIGH", "CRITICAL"))
                        .property("wait", JsonSchema.Property.bool(
                                "(Optional) Wait synchronously for the reply (default false)."))
                        .property("attachment", JsonSchema.Property.string(
                                "(Optional) Cloud-drive file path, e.g. 'Public/proposal.md'."))
                        .build(),
                (role, call) -> {
                    String targetName = Tools.argStripped(call, "target");
                    String message = Tools.arg(call, "message");
                    if (targetName.isEmpty() || message.isEmpty()) {
                        return ToolResult.error("talk: Error: 'target' and 'message' are required.");
                    }
                    Optional<RoleId> targetId = team.resolve(targetName);
                    if (targetId.isEmpty()) {
                        return ToolResult.error("talk: Error: cannot find '" + targetName
                                + "' in the team. Call list_roles to see the current member names.");
                    }
                    Optional<RoleSpec> sender = team.spec(role);
                    Optional<RoleSpec> target = team.spec(targetId.get());
                    if (sender.isEmpty() || target.isEmpty()) {
                        return ToolResult.error("talk: Error: unknown role");
                    }
                    if (sender.get().hasGroup() && target.get().hasGroup()
                            && !sender.get().group().equals(target.get().group())) {
                        return ToolResult.error("talk: Error: talk is only for the same group. "
                                + sender.get().name() + " is in \"" + sender.get().group() + "\", "
                                + target.get().name() + " is in \"" + target.get().group()
                                + "\". Use send_email for cross-group communication.");
                    }

                    String urgencyName = normalizeUrgency(Tools.argStripped(call, "urgency"));
                    int urgency = urgencyValue(urgencyName);
                    boolean wait = call.arguments().boolVal("wait", false);
                    String attachment = Tools.argStripped(call, "attachment");

                    if (!attachment.isEmpty()) {
                        String error = validateAttachment(role, attachment, computers);
                        if (error != null) {
                            return ToolResult.error(error);
                        }
                    }

                    Task task = buildTask(sender.get(), target.get(), message, attachment, wait, urgency);

                    if (wait && wouldDeadlock(team, targetId.get(), role)) {
                        return ToolResult.error("talk: Error: mutual-wait deadlock detected "
                                + "(the other party's wait chain loops back to you). "
                                + "Send a normal message instead.");
                    }

                    if (wait) {
                        Optional<String> reply = team.waitForReply(role, targetId.get(), task, null);
                        record(team, sender.get(), target.get(), message, urgencyName, recorder);
                        return ToolResult.success("talk: received reply from " + target.get().name() + ": "
                                + reply.orElse("(no reply)"));
                    }

                    if (team.deliverReply(targetId.get(), role, message)) {
                        record(team, sender.get(), target.get(), message, urgencyName, recorder);
                        return ToolResult.success("talk: replied to " + target.get().name() + " who was waiting.");
                    }

                    team.enqueue(targetId.get(), task);
                    record(team, sender.get(), target.get(), message, urgencyName, recorder);
                    return ToolResult.success("talk: message sent to " + target.get().name()
                            + ", urgency=" + urgencyName + ", recipient queue now has "
                            + team.pendingTasks(targetId.get()).size() + " tasks.");
                });

        Tool listRoles = Tools.of("list_roles", "List the current team members",
                JsonSchema.object(),
                (role, call) -> {
                    StringBuilder sb = new StringBuilder("list_roles: current team members:");
                    List<RoleSpec> members = team.members();
                    if (members.isEmpty()) {
                        return ToolResult.success("list_roles: (no team members currently)");
                    }
                    for (RoleSpec r : members) {
                        String skills = r.skills().size() > 4
                                ? String.join(", ", r.skills().subList(0, 4))
                                : String.join(", ", r.skills());
                        sb.append("\n  - ").append(r.name()).append(" -- ")
                                .append(r.responsibilities().isEmpty() ? r.title() : r.responsibilities())
                                .append("  (Group: ").append(r.hasGroup() ? r.group() : "Unassigned").append(")")
                                .append(skills.isEmpty() ? "" : "  Skills: " + skills);
                    }
                    return ToolResult.success(sb.toString());
                });

        return new Toolkit("talk", "Role communication toolkit: talk to a colleague, list the team",
                List.of(talk, listRoles));
    }

    private static Task buildTask(RoleSpec sender, RoleSpec target, String message,
                                  String attachment, boolean wait, int urgency) {
        StringBuilder description = new StringBuilder("[FROM talk] ").append(message);
        if (!attachment.isEmpty()) {
            description.append("\n[Attachment: ").append(attachment)
                    .append("] (company cloud drive file, readable under /mnt/drive)");
        }
        if (wait) {
            description.append("\n\n\u26a0 ").append(sender.name())
                    .append(" is waiting for your reply (wait=true). Please reply promptly with the talk tool.");
        }
        Payload context = Payload.of("message", message)
                .with("waiting", wait)
                .with("attachment", attachment);
        return Task.create(urgency, description.toString(), "talk", context);
    }

    private static String validateAttachment(RoleId role, String attachment,
                                             Function<RoleId, Optional<ComputerPort>> computers) {
        if (attachment.contains("..") || attachment.startsWith("/") || attachment.endsWith("/")) {
            return "talk: Error: invalid attachment path: '" + attachment
                    + "' (must be a relative cloud-drive path)";
        }
        if (computers == null) {
            return "talk: Error: no computer is available to verify the attachment.";
        }
        Optional<ComputerPort> computer = computers.apply(role);
        if (computer.isEmpty()) {
            return "talk: Error: no computer is assigned to " + role + ", cannot send an attachment.";
        }
        Optional<String> content = computer.get().readFile(computer.get().driveRoot() + "/" + attachment);
        if (content.isEmpty()) {
            return "talk: Error: attachment not found or not readable: '" + attachment + "'";
        }
        return null;
    }

    private static boolean wouldDeadlock(TeamPort team, RoleId start, RoleId sender) {
        Set<String> seen = new LinkedHashSet<>();
        Optional<RoleId> current = Optional.of(start);
        while (current.isPresent()) {
            RoleId id = current.get();
            if (team.stateOf(id) != AgentState.WAITING) {
                return false;
            }
            if (!seen.add(id.value())) {
                return true;
            }
            Optional<String> next = team.waitingReplyFrom(id);
            if (next.isEmpty()) {
                return false;
            }
            if (next.get().equals(sender.value())) {
                return true;
            }
            current = team.resolve(next.get());
        }
        return false;
    }

    private static void record(TeamPort team, RoleSpec sender, RoleSpec target,
                               String message, String urgency, Consumer<TalkRecord> recorder) {
        if (recorder == null) {
            return;
        }
        String group = sender.hasGroup() ? sender.group() : target.group();
        recorder.accept(new TalkRecord(sender.id(), sender.name(), group,
                target.id(), target.name(), message, urgency));
    }

    private static String normalizeUrgency(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NORMAL";
        }
        return switch (raw.strip().toUpperCase(java.util.Locale.ROOT)) {
            case "LOW", "HIGH", "CRITICAL" -> raw.strip().toUpperCase(java.util.Locale.ROOT);
            default -> "NORMAL";
        };
    }

    private static int urgencyValue(String urgency) {
        return switch (urgency) {
            case "LOW" -> 1;
            case "HIGH" -> 6;
            case "CRITICAL" -> 10;
            default -> 3;
        };
    }
}
