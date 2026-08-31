package com.maf.scheduler.tools.toolkits.talk;

import com.maf.scheduler.role.AgentRole;
import com.maf.scheduler.core.Computer;
import com.maf.scheduler.role.RolePool;
import com.maf.scheduler.core.Types;
import com.maf.scheduler.tools.Tool;

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
        schema.put("target", "目标成员姓名 (团队名单请先通过 list_roles 获取, 花名册里的姓名).");
        schema.put("message", "要发送的消息或委托的任务, 描述要具体.");
        schema.put("urgency", "(Optional) 紧急程度: LOW / NORMAL / HIGH / CRITICAL (生产事故用 CRITICAL).");
        schema.put("wait", "(Optional, default false) 是否等待对方回复. true = 同步等待, 收到回复后继续.");
        schema.put("attachment", "(Optional) 企业云盘文件路径 (如 'Public/方案.md'), 作为附件随消息发送.");
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
            return "talk: Error: 'target' 和 'message' 为必填参数.";
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
            return "talk: Error: 团队中找不到 '" + target + "'。请先调用 list_roles 查看当前成员姓名, 再用人名发送。";
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
                return "talk: Error: talk 工具仅限同组成员之间交流。"
                        + agentRole.name + " 属于「" + sGroup + "」, "
                        + targetRole.name + " 属于「" + tGroup + "」。"
                        + "跨组沟通请使用邮件 send_email 发送邮件 "
                        + "(可用 mail_address_book 查对方邮箱)。";
            }
        }

        // ── 附件校验: 云盘路径必须存在且当前角色可读 ──
        if (attachment != null) {
            if (agentRole == null) {
                return "talk: Error: 当前角色未绑定, 无法发送附件 (attachment).";
            }
            if (attachment.contains("..") || attachment.startsWith("/") || attachment.endsWith("/")) {
                return "talk: Error: 附件路径非法: '" + attachment + "' (须为云盘相对路径)";
            }
            try {
                Computer comp = agentRole.computer();
                String content = comp.readFile(comp.driveRoot() + "/" + attachment);
                if (Types.isFailureText(content)) {
                    return "talk: Error: 附件无效: 云盘文件不存在或不可读 '" + attachment + "'";
                }
            } catch (Exception exc) {
                return "talk: Error: 附件不可读: " + exc.getMessage();
            }
        }

        // ── 1) 回复投递: 目标正处于 WAIT 且在等我回复 → 直接投递唤醒 ──
        if (targetRole.state == Types.AgentState.WAIT
                && senderId != null && senderId.equals(targetRole.waitingReplyFrom())) {
            targetRole.deliverReply(message);
            if (agentRole != null) {
                agentRole.journal("回复了等待中的 " + targetRole.name + ": " + truncate(message, 80));
            }
            return "talk: 已回复给正在等待的 " + targetRole.name + ".";
        }

        // 构造任务: wait=true 时在消息中明确告知对方"提问者正在等待"
        String waitingHint = "";
        if (wait && agentRole != null) {
            waitingHint = "\n\n⚠️ " + agentRole.name + " 正在等待你的回复 (wait=true)。"
                    + "请优先处理这条消息, 尽快用 talk 工具回复对方。";
        }
        String attachHint = "";
        if (attachment != null) {
            attachHint = "\n[附件: " + attachment + "] (公司云盘文件, 在 /mnt/drive 下可直接读取)";
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
                return "talk: Error: 当前角色未绑定, 无法使用 wait=true。";
            }
            if (targetRole.state == Types.AgentState.WAIT
                    && wouldDeadlock(pool, targetRole, agentRole.roleId)) {
                return "talk: Error: 检测到互相等待死锁 (对方的等待链成环回到你)。"
                        + "请勿使用 wait=true, 改为普通消息或稍后再询问。";
            }
            agentRole.journal("发消息给 " + targetRole.name + " (" + urgency.name() + ", 等待回复): "
                    + truncate(message, 80));
            String reply = agentRole.talkWait(targetRole.roleId, task, null);  // 无限等待
            return "talk: 已收到 " + targetRole.name + " 的回复: " + reply;
        }

        // ── 3) 普通消息: 入目标队列, 立即返回 ──
        targetRole.addTask(task);
        if (agentRole != null) {
            agentRole.journal("发消息给 " + targetRole.name + " (" + urgency.name() + "): "
                    + truncate(message, 80));
        }
        return "talk: 消息已发送给 " + targetRole.name + ", 紧急度=" + urgency.name()
                + ", 对方队列现有 " + targetRole.queueDepth() + " 个任务.";
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
