package com.agent.software.tools.toolkits.talk;

import com.agent.software.computers.Computer;
import com.agent.software.role.AgentRole;
import com.agent.software.role.RolePool;
import com.agent.software.core.Types;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * talk — 给团队成员发送消息或委托任务.
 *
 * ⚠️ talk 仅限同组成员之间交流; 跨组沟通请使用邮件 send_email.
 * target 参数使用成员姓名 (见 list_roles 花名册).
 * wait=true 表示需要对方回复后才能继续 (同步等待); 普通通知/委托请用
 * wait=false (默认), 不要互相 wait 以免死锁.
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

        // target 是人名 (LLM 视角); 内部按人名→role_id 映射 (兼容 role_id 回退)
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

        // ── 组内交流限制: talk 只允许发给同组成员 (跨组请用邮件) ──
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

        // ── 附件校验: 云盘路径必须存在且当前角色可读 ──
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

        // ── 1) 回复投递: 目标正处于 WAIT 且在等我回复 → 直接投递唤醒 ──
        if (targetRole.state == Types.AgentState.WAIT
                && senderId != null && senderId.equals(targetRole.waitingReplyFrom())) {
            targetRole.deliverReply(message);
            if (agentRole != null) {
                agentRole.journal("回复了等待中的 " + targetRole.name + ": " + truncate(message, 80));
            }
            return "talk: replied to " + targetRole.name + " who was waiting.";
        }

        // 构造任务: wait=true 时在消息中明确告知对方"提问者正在等待"
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

        // ── 2) wait=true: 无限等待对方回复 ──
        if (wait) {
            if (agentRole == null) {
                return "talk: Error: current role is not bound, cannot use wait=true.";
            }
            if (targetRole.state == Types.AgentState.WAIT
                    && wouldDeadlock(pool, targetRole, agentRole.roleId)) {
                return "talk: Error: mutual-wait deadlock detected (the other party's wait chain loops back to you). "
                        + "Do not use wait=true; send a normal message or ask again later.";
            }
            agentRole.journal("发消息给 " + targetRole.name + " (" + urgency.name() + ", 等待回复): "
                    + truncate(message, 80));
            String reply = agentRole.talkWait(targetRole.roleId, task, null);  // 无限等待
            return "talk: received reply from " + targetRole.name + ": " + reply;
        }

        // ── 3) 普通消息: 入目标队列, 立即返回 ──
        targetRole.addTask(task);
        if (agentRole != null) {
            agentRole.journal("发消息给 " + targetRole.name + " (" + urgency.name() + "): "
                    + truncate(message, 80));
        }
        return "talk: message sent to " + targetRole.name + ", urgency=" + urgency.name()
                + ", recipient queue now has " + targetRole.queueDepth() + " tasks.";
    }

    /** 沿 WAIT 等待链查环: startRole 的等待链上若绕回 senderId 则成环 (互等死锁). */
    private static boolean wouldDeadlock(RolePool pool, AgentRole startRole, String senderId) {
        Set<String> seen = new LinkedHashSet<>();
        AgentRole cur = startRole;
        while (cur != null && cur.state == Types.AgentState.WAIT
                && cur.waitingReplyFrom() != null && !cur.waitingReplyFrom().isEmpty()) {
            if (seen.contains(cur.roleId)) {
                return true;  // 等待链自身成环
            }
            seen.add(cur.roleId);
            String nextId = cur.waitingReplyFrom();
            if (senderId.equals(nextId)) {
                return true;  // 等待链绕回发送者 → 互等死锁
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
