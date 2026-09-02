package com.agent.software.tools.toolkits.talk;

import com.agent.software.computers.Computer;
import com.agent.software.role.AgentRole;
import com.agent.software.role.RolePool;
import com.agent.software.core.Types;
import com.agent.software.tools.Tool;
import com.agent.software.web.ChatStore;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * talk — send a message to a team member or delegate a task.
 *
 * ⚠️ talk is only for communication between members of the same group; for cross-group communication use the email tool send_email.
 * The target parameter uses the member name (see the list_roles roster).
 * wait=true means you need the other party's reply before continuing (synchronous wait); for normal notifications/delegation use
 * wait=false (default), and do not wait on each other to avoid deadlock.
 */
public class TalkTo extends Tool {

    private final AgentRole agentRole;
    private final RolePool pool;

    public TalkTo(AgentRole agentRole, RolePool pool) {
        super();
        this.agentRole = agentRole;
        this.pool = pool;
    }

    @Override
    public String getToolName() {
        return "talk";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("target", "Target member name (get the team roster first via list_roles; use the names from the roster).");
        schema.put("message", "The message or delegated task to send; describe it specifically.");
        schema.put("urgency", "(Optional) Urgency level: LOW / NORMAL / HIGH / CRITICAL (use CRITICAL for production incidents).");
        schema.put("wait", "(Optional, default false) Whether to wait for the other party's reply. true = wait synchronously and continue once a reply is received.");
        schema.put("attachment", "(Optional) Company cloud drive file path (e.g. 'Public/proposal.md') to attach to the message.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object otarget = args.get("target");
        Object omessage = args.get("message");
        if (!(otarget instanceof String)) {
            return otarget == null
                    ? "talk: Error: needs target"
                    : "talk: Error: target is not a string";
        }
        if (!(omessage instanceof String)) {
            return omessage == null
                    ? "talk: Error: needs message"
                    : "talk: Error: message is not a string";
        }
        String target = ((String) otarget).strip();
        String message = (String) omessage;
        if (target.isEmpty() || message.isEmpty()) {
            return "talk: Error: 'target' and 'message' are required parameters.";
        }
        Object ourgency = args.get("urgency");
        String urgencyStr = ourgency instanceof String s ? s : "NORMAL";
        Object oattachment = args.get("attachment");
        String attachment = oattachment instanceof String s && !s.strip().isEmpty() ? s.strip() : null;
        Object owait = args.get("wait");
        boolean wait = owait instanceof Boolean b ? b
                : (owait instanceof String s && s.toLowerCase().matches("1|true|yes|on"));

        // target is a person's name (LLM perspective); internally map name → role_id (with role_id fallback for compatibility)
        AgentRole targetRole = pool.getRoleByName(target);
        if (targetRole == null) {
            return "talk: Error: cannot find '" + target + "' in the team. Please call list_roles first to see the current member names, then send using a member name.";
        }
        AgentRole.Urgency urgency;
        try {
            urgency = AgentRole.Urgency.valueOf(urgencyStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            urgency = AgentRole.Urgency.NORMAL;
        }
        String senderId = agentRole != null ? agentRole.roleId : null;

        // ── Intra-group communication restriction: talk is only allowed to members of the same group (use email for cross-group) ──
        if (agentRole != null) {
            String sGroup = (agentRole.group == null ? "" : agentRole.group).strip();
            String tGroup = (targetRole.group == null ? "" : targetRole.group).strip();
            if (!sGroup.isEmpty() && !tGroup.isEmpty() && !sGroup.equals(tGroup)) {
                return "talk: Error: the talk tool is only for communication within the same group. "
                        + agentRole.name + " belongs to \"" + sGroup + "\", "
                        + targetRole.name + " belongs to \"" + tGroup + "\". "
                        + "For cross-group communication, please use the email tool send_email "
                        + "(you can use mail_address_book to look up the recipient's email address).";
            }
        }

        // ── Attachment validation: the cloud drive path must exist and be readable by the current role ──
        if (attachment != null) {
            if (agentRole == null) {
                return "talk: Error: current role is not bound, cannot send attachment (attachment).";
            }
            if (attachment.contains("..") || attachment.startsWith("/") || attachment.endsWith("/")) {
                return "talk: Error: invalid attachment path: '" + attachment + "' (must be a relative cloud drive path)";
            }
            try {
                Computer comp = agentRole.computer();
                String content = comp.readFile(comp.driveRoot() + "/" + attachment);
                if (Types.isFailureText(content)) {
                    return "talk: Error: invalid attachment: cloud drive file does not exist or is not readable '" + attachment + "'";
                }
            } catch (Exception exc) {
                return "talk: Error: attachment not readable: " + exc.getMessage();
            }
        }

        // ── 1) Reply delivery: the target is in WAIT and waiting for my reply → deliver directly to wake it up ──
        if (targetRole.state == Types.AgentState.WAIT
                && senderId != null && senderId.equals(targetRole.waitingReplyFrom())) {
            targetRole.deliverReply(message);
            if (agentRole != null) {
                agentRole.journal("Replied to waiting " + targetRole.name + ": " + truncate(message, 80));
            }
            recordTalk(targetRole, message, urgency.name());
            return "talk: replied to " + targetRole.name + " who was waiting.";
        }

        // Build the task: when wait=true, explicitly tell the other party in the message that "the asker is waiting"
        String waitingHint = "";
        if (wait && agentRole != null) {
            waitingHint = "\n\n⚠️ " + agentRole.name + " is waiting for your reply (wait=true). "
                    + "Please prioritize this message and reply to them promptly using the talk tool.";
        }
        String attachHint = "";
        if (attachment != null) {
            attachHint = "\n[Attachment: " + attachment + "] (company cloud drive file, readable directly under /mnt/drive)";
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("message", message);
        ctx.put("waiting", wait);
        ctx.put("attachment", attachment);
        AgentRole.Task task = new AgentRole.Task(urgency.value,
                "[FROM talk] " + message + attachHint + waitingHint, "talk", ctx);

        // ── 2) wait=true: wait indefinitely for the other party's reply ──
        if (wait) {
            if (agentRole == null) {
                return "talk: Error: current role is not bound, cannot use wait=true.";
            }
            if (targetRole.state == Types.AgentState.WAIT
                    && wouldDeadlock(pool, targetRole, agentRole.roleId)) {
                return "talk: Error: mutual-wait deadlock detected (the other party's wait chain loops back to you). "
                        + "Do not use wait=true; send a normal message or ask again later.";
            }
            agentRole.journal("Sent message to " + targetRole.name + " (" + urgency.name() + ", waiting for reply): "
                    + truncate(message, 80));
            recordTalk(targetRole, message, urgency.name());
            String reply = agentRole.talkWait(targetRole.roleId, task, null);  // wait indefinitely
            return "talk: received reply from " + targetRole.name + ": " + reply;
        }

        // ── 3) Normal message: enqueue to the target, return immediately ──
        targetRole.addTask(task);
        if (agentRole != null) {
            agentRole.journal("Sent message to " + targetRole.name + " (" + urgency.name() + "): "
                    + truncate(message, 80));
        }
        recordTalk(targetRole, message, urgency.name());
        return "talk: message sent to " + targetRole.name + ", urgency=" + urgency.name()
                + ", recipient queue now has " + targetRole.queueDepth() + " tasks.";
    }

    /**
     * Record intra-group talk messages to the chat store (displayed on the Web UI). If the sender is not bound to a system
     * (no ChatStore), skip silently — standalone roles/unit test behavior is unchanged.
     * Message group attribution: the sender's group first, otherwise the recipient's group (when an ungrouped new member
     * sends to a member of a group, it shows in the recipient's group).
     */
    private void recordTalk(AgentRole targetRole, String message, String urgency) {
        ChatStore store = agentRole != null && agentRole.system() != null
                ? agentRole.system().chatStore : null;
        if (store == null || targetRole == null) {
            return;
        }
        String group = agentRole.group;
        if (group == null || group.isBlank()) {
            group = targetRole.group == null ? "" : targetRole.group;
        }
        store.record(ChatStore.KIND_TALK, group, agentRole.roleId, agentRole.name,
                targetRole.roleId, targetRole.name, message, urgency);
    }

    /** Follow the WAIT chain to detect a cycle: if startRole's wait chain loops back to senderId, there is a cycle (mutual-wait deadlock). */
    private static boolean wouldDeadlock(RolePool pool, AgentRole startRole, String senderId) {
        Set<String> seen = new LinkedHashSet<>();
        AgentRole cur = startRole;
        while (cur != null && cur.state == Types.AgentState.WAIT
                && cur.waitingReplyFrom() != null && !cur.waitingReplyFrom().isEmpty()) {
            if (seen.contains(cur.roleId)) {
                return true;  // the wait chain itself forms a cycle
            }
            seen.add(cur.roleId);
            String nextId = cur.waitingReplyFrom();
            if (senderId.equals(nextId)) {
                return true;  // the wait chain loops back to the sender → mutual-wait deadlock
            }
            cur = pool.getRoleOrNull(nextId);
        }
        return false;
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }
}
