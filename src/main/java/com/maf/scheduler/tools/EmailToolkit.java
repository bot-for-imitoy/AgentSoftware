package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.utils.Json;
import com.maf.scheduler.services.MailService;
import com.maf.scheduler.core.RolePool;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.core.ToolRegistry.ToolHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公司邮件工具类 (Email ToolKit) — Python 版 email_toolkit.py.
 *
 * 包含: send_email / read_mail / open_mail / mail_address_book.
 */
public final class EmailToolkit {

    private EmailToolkit() {
    }

    /** 创建邮件工具类. */
    public static ToolKit createEmailToolkit(MailService service) {
        MailService svc = service != null ? service : MailService.getMailService();
        ToolKit tk = new ToolKit("email", "公司邮件工具类 (员工邮件收发)");
        tk.bind("mail", svc);  // 工厂即绑定服务; 角色由 binder 绑定

        ToolHandler sendEmail = args -> {
            Object toRaw = args.get("to");
            String subject = Json.str(args, "subject", "").strip();
            String body = Json.str(args, "body", "");
            Object ccRaw = args.get("cc");
            if (toRaw == null || String.valueOf(toRaw).strip().isEmpty()) {
                return "错误: 'to' (收件人) 为必填参数.";
            }
            if (subject.isEmpty() && body.strip().isEmpty()) {
                return "错误: 'subject' 与 'body' 至少填一个.";
            }
            AgentRole role = (AgentRole) tk.require("role", "当前角色");
            RolePool pool = role.pool();
            List<String> failed = new ArrayList<>();
            List<String> to = resolveRecipients(pool, toRaw, svc, failed);
            List<String> cc = new ArrayList<>();
            List<String> ccFailed = new ArrayList<>();
            if (ccRaw != null) {
                cc = resolveRecipients(pool, ccRaw, svc, ccFailed);
                failed.addAll(ccFailed);
            }
            if (to.isEmpty()) {
                return "错误: 收件人无法解析: " + String.join(", ", failed.isEmpty() ? List.of("(空)") : failed)
                        + "。请先调用 mail_address_book 查看成员姓名/邮箱。";
            }
            String senderEmail = svc.emailFor(role);
            String result = svc.send(senderEmail, role.name, to, subject, body, cc);
            if (!failed.isEmpty()) {
                result += " 注意: 以下收件人未找到, 未发送: " + String.join(", ", failed);
            }
            role.journal("发送邮件: 「" + subject + "」 → " + String.join(", ", to));
            return result;
        };

        ToolHandler readMail = args -> {
            AgentRole role = (AgentRole) tk.require("role", "当前角色");
            String email = svc.emailFor(role);
            int limit;
            try {
                Object l = args.get("limit");
                limit = l instanceof Number n ? n.intValue() : (l != null ? Integer.parseInt(String.valueOf(l)) : 10);
            } catch (NumberFormatException e) {
                limit = 10;
            }
            boolean unreadOnly = Json.boolVal(args, "unread_only", false);
            List<MailService.MailMessage> msgs = svc.inbox(email, null);
            if (unreadOnly) {
                msgs.removeIf(m -> m.read);
            }
            if (msgs.size() > Math.max(0, limit)) {
                msgs = new ArrayList<>(msgs.subList(0, Math.max(0, limit)));
            }
            if (msgs.isEmpty()) {
                return "收件箱为空 (邮箱 " + email + ").";
            }
            List<String> lines = new ArrayList<>();
            lines.add("收件箱 " + email + " (" + svc.unreadCount(email) + " 封未读):");
            int i = 1;
            for (MailService.MailMessage m : msgs) {
                lines.add("  " + i + ". " + m.preview());
                i++;
            }
            return String.join("\n", lines);
        };

        ToolHandler openMail = args -> {
            String messageId = Json.str(args, "message_id", "").strip();
            if (messageId.isEmpty()) {
                return "错误: 'message_id' 为必填参数 (read_mail 列表中的 id).";
            }
            AgentRole role = (AgentRole) tk.require("role", "当前角色");
            MailService.MailMessage msg = svc.read(svc.emailFor(role), messageId);
            if (msg == null) {
                return "错误: 找不到邮件 " + messageId + "。请先调用 read_mail 查看当前收件箱中的邮件。";
            }
            return msg.fullText();
        };

        ToolHandler addressBook = args -> {
            AgentRole role = (AgentRole) tk.require("role", "当前角色");
            RolePool pool = role.pool();
            String groupFilter = Json.str(args, "group", "").strip();
            if (pool == null) {
                return "错误: 当前角色未绑定角色池, 无法获取通讯录.";
            }
            Map<String, List<AgentRole>> byGroup = new LinkedHashMap<>();
            for (AgentRole r : pool.allRoles()) {
                String g = (r.group == null ? "" : r.group).strip();
                if (g.isEmpty()) {
                    g = "未分组";
                }
                byGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(r);
            }
            List<String> lines = new ArrayList<>();
            lines.add("公司通讯录 (邮箱后缀 @" + svc.config.suffix + ", 共 " + pool.allRoles().size() + " 人):");
            List<String> groups = new ArrayList<>(byGroup.keySet());
            groups.sort(String::compareTo);
            for (String g : groups) {
                if (!groupFilter.isEmpty() && !groupFilter.equals(g)) {
                    continue;
                }
                lines.add("【" + g + "】");
                List<AgentRole> members = byGroup.get(g);
                members.sort((a, b) -> a.name.compareTo(b.name));
                for (AgentRole r : members) {
                    String desc = !r.title.isEmpty() ? r.title : (!r.responsibilities.isEmpty() ? r.responsibilities : "团队成员");
                    lines.add("  - " + r.name + " <" + svc.emailFor(r) + "> — " + desc);
                }
            }
            if (!groupFilter.isEmpty() && !byGroup.containsKey(groupFilter)) {
                return "错误: 找不到分组「" + groupFilter + "」。可用分组: " + String.join(", ", groups);
            }
            return String.join("\n", lines);
        };

        Map<String, Object> sendSchema = new LinkedHashMap<>();
        sendSchema.put("type", "object");
        sendSchema.put("properties", Map.of(
                "to", TalkToolkit.mapOf("string", "收件人: 同事姓名或邮箱, 多人用逗号分隔"),
                "subject", TalkToolkit.mapOf("string", "邮件主题 (一句话概括)"),
                "body", TalkToolkit.mapOf("string", "邮件正文 (具体内容, 越详细对方越好处理)"),
                "cc", TalkToolkit.mapOf("string", "抄送 (可选): 姓名或邮箱, 多人用逗号分隔")));
        sendSchema.put("required", List.of("to", "subject", "body"));

        Map<String, Object> readSchema = new LinkedHashMap<>();
        readSchema.put("type", "object");
        readSchema.put("properties", Map.of(
                "limit", TalkToolkit.mapOf("integer", "最多显示条数 (默认 10)"),
                "unread_only", TalkToolkit.mapOf("boolean", "是否只看未读邮件 (默认 false)")));

        Map<String, Object> openSchema = new LinkedHashMap<>();
        openSchema.put("type", "object");
        openSchema.put("properties", Map.of(
                "message_id", TalkToolkit.mapOf("string", "邮件 id (read_mail 返回)")));
        openSchema.put("required", List.of("message_id"));

        Map<String, Object> bookSchema = new LinkedHashMap<>();
        bookSchema.put("type", "object");
        bookSchema.put("properties", Map.of(
                "group", TalkToolkit.mapOf("string", "只看某分组 (可选, 如 '前端开发组')")));

        tk.addPythonTool("send_email",
                "给同事发送公司邮件 (员工之间正式沟通/跨组沟通的方式). "
                        + "to 用同事姓名 (见 mail_address_book 通讯录) 或完整邮箱地址; "
                        + "可一次发给多人 (逗号分隔或数组), 需要抄送时用 cc. "
                        + "发送成功后邮件进入对方收件箱 (read_mail), 对方可回复. "
                        + "跨组同事沟通请用邮件而不是 talk (talk 仅限同组成员).",
                sendSchema, sendEmail);
        tk.addPythonTool("read_mail",
                "查看自己的公司邮箱收件箱 (新到优先). "
                        + "返回邮件列表: 发件人/主题/摘要/已读未读/message_id. "
                        + "用 open_mail 打开某封邮件的完整内容. "
                        + "每天开始或空闲时可以查看是否有新邮件.",
                readSchema, readMail);
        tk.addPythonTool("open_mail",
                "打开一封邮件查看完整内容 (自动标记为已读). message_id 来自 read_mail 列表.",
                openSchema, openMail);
        tk.addPythonTool("mail_address_book",
                "查看公司通讯录: 所有成员按分组列出 (组名 → 姓名 <邮箱> — 职位). "
                        + "发邮件前若不确定收件人姓名或邮箱, 先调用本工具. "
                        + "也可用 group 参数只看某个分组.",
                bookSchema, addressBook);
        return tk;
    }

    /** 把一个人名/邮箱解析为邮箱地址; 无法解析返回空串. */
    private static String resolveAddress(RolePool pool, String value, MailService svc) {
        String v = (value == null ? "" : value).strip();
        if (v.isEmpty()) {
            return "";
        }
        if (v.contains("@")) {
            return v;
        }
        if (pool == null) {
            return "";
        }
        AgentRole role = pool.getRoleByName(v);
        if (role == null) {
            return "";
        }
        return svc.emailFor(role);
    }

    /** 批量解析收件人 (人名或邮箱混合, 支持列表/逗号分隔字符串). */
    private static List<String> resolveRecipients(RolePool pool, Object values,
                                                  MailService svc, List<String> failed) {
        List<String> parts = new ArrayList<>();
        if (values instanceof String s) {
            for (String v : s.split(",")) {
                if (!v.strip().isEmpty()) {
                    parts.add(v.strip());
                }
            }
        } else if (values instanceof List<?> list) {
            for (Object v : list) {
                if (String.valueOf(v).strip().isEmpty()) {
                    continue;
                }
                parts.add(String.valueOf(v).strip());
            }
        } else if (values != null) {
            parts.add(String.valueOf(values).strip());
        }
        List<String> emails = new ArrayList<>();
        for (String p : parts) {
            String addr = resolveAddress(pool, p, svc);
            if (!addr.isEmpty()) {
                emails.add(addr);
            } else {
                failed.add(p);
            }
        }
        return emails;
    }

    /** 将当前角色绑定到邮件工具类. */
    public static void bindEmailToToolkit(ToolKit toolkit, AgentRole role) {
        toolkit.bind("role", role);
    }
}
