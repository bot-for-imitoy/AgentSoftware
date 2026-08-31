package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.computers.Computer;
import com.maf.scheduler.utils.Json;
import com.maf.scheduler.core.RolePool;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.core.ToolRegistry.ToolHandler;
import com.maf.scheduler.core.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通信工具类 (Communication ToolKit) — Python 版 talk_toolkit.py.
 *
 * 包含:
 *   - talk:       角色之间的消息传递与任务委托 (wait=true 同步等待回复)
 *   - list_roles: 获取当前团队角色列表 (花名册)
 */
public final class TalkToolkit {

    private static final Logger logger = LoggerFactory.getLogger(TalkToolkit.class);

    private TalkToolkit() {
    }

    /** 构建团队花名册 (固定格式, 供 talk 描述与 list_roles 工具复用). */
    public static String buildTeamRoster(RolePool pool) {
        List<String> rosterLines = new ArrayList<>();
        for (AgentRole r : pool.allRoles()) {
            String resp = !r.responsibilities.isEmpty() ? r.responsibilities : r.title;
            String group = (r.group == null ? "" : r.group).strip();
            if (group.isEmpty()) {
                group = "未分组";
            }
            List<String> skills = r.skills.size() > 4 ? r.skills.subList(0, 4) : r.skills;
            rosterLines.add("  - **" + r.name + "** -- " + resp + "  (组: " + group + ")  "
                    + "Skills: " + String.join(", ", skills));
        }
        return String.join("\n", rosterLines);
    }

    /** 沿 WAIT 等待链查环: startRole 的等待链上若绕回 senderId 则成环 (互等死锁). */
    static boolean wouldDeadlock(RolePool pool, AgentRole startRole, String senderId) {
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

    /** 创建通信工具类 (talk + list_roles). */
    public static ToolKit createTalkToolkit(RolePool pool) {
        ToolKit tk = new ToolKit("communication", "角色间通信工具类");

        ToolHandler talkHandler = args -> {
            String target = Json.str(args, "target", "");
            String message = Json.str(args, "message", "");
            String urgencyStr = Json.str(args, "urgency", "NORMAL");
            String attachment = Json.str(args, "attachment", "").strip();
            if (attachment.isEmpty()) {
                attachment = null;
            }
            Object waitVal = args.get("wait");
            boolean wait;
            if (waitVal instanceof String s) {
                wait = s.toLowerCase().matches("1|true|yes|on");
            } else {
                wait = waitVal instanceof Boolean b && b;
            }

            if (target.isEmpty() || message.isEmpty()) {
                return "错误: 'target' 和 'message' 为必填参数.";
            }
            // target 是人名 (LLM 视角); 内部按人名→role_id 映射 (兼容 role_id 回退)
            AgentRole targetRole = pool.getRoleByName(target);
            if (targetRole == null) {
                return "错误: 团队中找不到 '" + target + "'。请先调用 list_roles 查看当前成员姓名, 再用人名发送。";
            }
            AgentRole.Urgency urgency;
            try {
                urgency = AgentRole.Urgency.valueOf(urgencyStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                urgency = AgentRole.Urgency.NORMAL;
            }
            AgentRole sender = (AgentRole) tk.get("role", null);
            String senderId = sender != null ? sender.roleId : null;

            // ── 组内交流限制: talk 只允许发给同组成员 (跨组请用邮件) ──
            if (sender != null) {
                String sGroup = (sender.group == null ? "" : sender.group).strip();
                String tGroup = (targetRole.group == null ? "" : targetRole.group).strip();
                if (!sGroup.isEmpty() && !tGroup.isEmpty() && !sGroup.equals(tGroup)) {
                    return "错误: talk 工具仅限同组成员之间交流。"
                            + sender.name + " 属于「" + sGroup + "」, "
                            + targetRole.name + " 属于「" + tGroup + "」。"
                            + "跨组沟通请使用邮件 send_email 发送邮件 "
                            + "(可用 mail_address_book 查对方邮箱)。";
                }
            }

            // ── 附件校验: 云盘路径必须存在且当前角色可读 ──
            if (attachment != null) {
                if (sender == null) {
                    return "错误: 当前角色未绑定, 无法发送附件 (attachment).";
                }
                if (attachment.contains("..") || attachment.startsWith("/") || attachment.endsWith("/")) {
                    return "错误: 附件路径非法: '" + attachment + "' (须为云盘相对路径)";
                }
                try {
                    Computer comp = sender.computer();
                    String content = comp.readFile(comp.driveRoot() + "/" + attachment);
                    if (Types.isFailureText(content)) {
                        return "错误: 附件无效: 云盘文件不存在或不可读 '" + attachment + "'";
                    }
                } catch (Exception exc) {
                    return "错误: 附件不可读: " + exc.getMessage();
                }
            }

            // ── 1) 回复投递: 目标正处于 WAIT 且在等我回复 → 直接投递唤醒 ──
            if (targetRole.state == Types.AgentState.WAIT
                    && senderId != null && senderId.equals(targetRole.waitingReplyFrom())) {
                targetRole.deliverReply(message);
                if (sender != null) {
                    sender.journal("回复了等待中的 " + targetRole.name + ": " + truncate(message, 80));
                }
                return "已回复给正在等待的 " + targetRole.name + ".";
            }

            // 构造任务: wait=true 时在消息中明确告知对方"提问者正在等待"
            String waitingHint = "";
            if (wait && sender != null) {
                waitingHint = "\n\n⚠️ " + sender.name + " 正在等待你的回复 (wait=true)。"
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
                if (sender == null) {
                    return "错误: 当前角色未绑定, 无法使用 wait=true。";
                }
                if (targetRole.state == Types.AgentState.WAIT
                        && wouldDeadlock(pool, targetRole, sender.roleId)) {
                    return "错误: 检测到互相等待死锁 (对方的等待链成环回到你)。"
                            + "请勿使用 wait=true, 改为普通消息或稍后再询问。";
                }
                sender.journal("发消息给 " + targetRole.name + " (" + urgency.name() + ", 等待回复): "
                        + truncate(message, 80));
                String reply = sender.talkWait(targetRole.roleId, task, null);  // 无限等待
                return "已收到 " + targetRole.name + " 的回复: " + reply;
            }

            // ── 3) 普通消息: 入目标队列, 立即返回 ──
            targetRole.addTask(task);
            if (sender != null) {
                sender.journal("发消息给 " + targetRole.name + " (" + urgency.name() + "): "
                        + truncate(message, 80));
            }
            return "消息已发送给 " + targetRole.name + ", 紧急度=" + urgency.name()
                    + ", 对方队列现有 " + targetRole.queueDepth() + " 个任务.";
        };

        ToolHandler listRolesHandler = args -> {
            String roster = buildTeamRoster(pool);
            if (roster.isEmpty()) {
                return "(当前无团队成员)";
            }
            return "当前团队成员:\n" + roster;
        };

        Map<String, Object> talkSchema = new LinkedHashMap<>();
        talkSchema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("target", mapOf("string", "目标成员姓名 (团队名单请先通过 list_roles 获取, 花名册里的姓名)"));
        props.put("message", mapOf("string", "要发送的消息或委托的任务, 描述要具体."));
        Map<String, Object> urgencyProp = mapOf("string", "紧急程度, 生产事故用 CRITICAL.");
        urgencyProp.put("enum", List.of("LOW", "NORMAL", "HIGH", "CRITICAL"));
        props.put("urgency", urgencyProp);
        props.put("wait", mapOf("boolean", "是否等待对方回复 (默认 false). true = 同步等待, 收到回复后继续."));
        props.put("attachment", mapOf("string", "可选: 企业云盘文件路径 (如 'Public/方案.md'), 作为附件随消息发送"));
        talkSchema.put("properties", props);
        talkSchema.put("required", List.of("target", "message"));

        tk.addPythonTool("talk",
                "给团队成员发送消息或委托任务. "
                        + "⚠️ 重要: talk 仅限同组成员之间交流 (花名册里有每个人的分组). "
                        + "跨组沟通请使用邮件 send_email (公司邮箱, 可用 mail_address_book 查对方邮箱). "
                        + "团队当前有哪些成员请先调用 list_roles 获取 (名单是动态的, 可能有新入职). "
                        + "根据每个人的职责选择合适的人选后, 用 target 发送.\n"
                        + "target 参数使用成员姓名 (见 list_roles 花名册, 例如 '王建国').\n"
                        + "attachment 可选: 公司云盘文件路径 (如 'Public/方案.md' 或 '郭晓东/设计稿.md'), "
                        + "作为附件随消息发送 (文件在 /mnt/drive 下); 发送前系统会校验文件存在.\n"
                        + "wait=true 表示需要对方回复后才能继续 (同步等待): 你会进入 WAIT 状态, "
                        + "消息会附带'你正在等待回复'的提示, 对方收到后应尽快用 talk 回复你; "
                        + "收到回复后工具返回回复内容并恢复原状态. "
                        + "等待没有时间限制 (LLM 输出时间不可预测). "
                        + "仅在确实需要对方答案才能继续时才用 wait=true; "
                        + "普通通知/委托请用 wait=false (默认), 不要互相 wait 以免死锁.",
                talkSchema, talkHandler);

        tk.addPythonTool("list_roles",
                "获取当前团队都有哪些成员 (姓名/职责/技能/所属分组). "
                        + "在向同事发消息前, 或不确定该找谁处理某件事时, 先调用此工具查看团队成员, "
                        + "然后用 talk 给同组成员发消息, 或用 send_email 给任何同事发邮件.",
                emptySchema(), listRolesHandler);

        return tk;
    }

    static Map<String, Object> mapOf(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    static Map<String, Object> emptySchema() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", new LinkedHashMap<>());
        return m;
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }
}
