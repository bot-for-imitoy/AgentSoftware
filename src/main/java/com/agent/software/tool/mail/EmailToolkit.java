package com.agent.software.tool.mail;

import com.agent.software.agent.AgentDirectory;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.MailId;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolkit;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 邮件工具包（id {@code "email"}），暴露工具：send_email / read_mail / open_mail / mail_address_book。
 *
 * <p>发件地址一律来自 {@link Mailbox#addressOf(RoleSpec)}，本类不再拼第二份；收件人支持
 * "姓名或完整邮箱、逗号分隔"，与 master 的 {@code SendEmail} 行为一致。
 */
public final class EmailToolkit implements Toolkit {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Mailbox mail;
    private final AgentDirectory directory;

    public EmailToolkit(Mailbox mail, AgentDirectory directory) {
        this.mail = mail;
        this.directory = directory;
    }

    @Override
    public String id() {
        return "email";
    }

    @Override
    public List<Tool> instantiate() {
        return List.of(new SendEmailTool(), new ReadMailTool(), new OpenMailTool(), new AddressBookTool());
    }

    // ── 工具声明 ───────────────────────────────────────────────

    private final class SendEmailTool implements Tool {
        @Override
        public ToolSpec spec() {
            return new ToolSpec("send_email",
                    "给同事发送公司邮件（跨组正式沟通用）。to/cc 可填同事姓名或完整邮箱，多个用逗号分隔。",
                    JsonSchema.object()
                            .string("to", "收件人：同事姓名或邮箱，多个用逗号分隔。")
                            .string("subject", "邮件主题（一句话）。")
                            .string("body", "邮件正文（写得越具体越好）。")
                            .string("cc", "（可选）抄送：姓名或邮箱，多个用逗号分隔。")
                            .required("to", "subject", "body"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String toRaw = arguments.stringOr("to", "").trim();
            String subject = arguments.stringOr("subject", "").trim();
            String body = Text.orEmpty(arguments.stringOr("body", ""));
            String ccRaw = arguments.stringOr("cc", "").trim();
            if (toRaw.isEmpty()) {
                return ToolResult.error("send_email：缺少收件人参数 to。");
            }
            if (subject.isEmpty() || Text.isBlank(body)) {
                return ToolResult.error("send_email：subject 与 body 均为必填项。");
            }
            List<String> unresolved = new ArrayList<>();
            List<String> to = resolveRecipients(toRaw, unresolved);
            List<String> cc = ccRaw.isEmpty() ? new ArrayList<>() : resolveRecipients(ccRaw, unresolved);
            if (to.isEmpty()) {
                return ToolResult.error("send_email：无法解析收件人 "
                        + (unresolved.isEmpty() ? "（收件人为空）" : String.join("、", unresolved))
                        + "。请先调用 mail_address_book 查询同事姓名或邮箱。");
            }
            RoleSpec spec = caller(agent);
            String fromAddress = mail.addressOf(spec);
            try {
                MailId id = mail.send(new Mailbox.OutgoingMail(
                        fromAddress, spec.name(), to, cc, subject, body));
                StringBuilder sb = new StringBuilder("send_email：已发送给 ")
                        .append(String.join("、", to))
                        .append("，主题《").append(subject).append("》，邮件 id=").append(id.value()).append("。");
                if (!unresolved.isEmpty()) {
                    sb.append(" 未能解析、未发送的收件人：").append(String.join("、", unresolved)).append("。");
                }
                return ToolResult.ok(sb.toString());
            } catch (DomainError e) {
                return ToolResult.error("send_email：发送失败 - " + e.getMessage());
            }
        }
    }

    private final class ReadMailTool implements Tool {
        @Override
        public ToolSpec spec() {
            return new ToolSpec("read_mail",
                    "查看自己的公司邮箱收件箱（最新的在前），返回发件人/主题/摘要/已读状态/邮件 id；用 open_mail 打开全文。",
                    JsonSchema.object()
                            .integer("limit", "（可选）最多显示多少封，默认 10；传 0 表示全部。"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            RoleSpec spec = caller(agent);
            String address = mail.addressOf(spec);
            int limit = arguments.intValue("limit").orElse(10);
            List<MailMessage> messages = mail.inbox(address, limit);
            int unread = mail.unreadCount(address);
            if (messages.isEmpty()) {
                return ToolResult.ok("read_mail：收件箱为空（" + address + "）。");
            }
            List<String> lines = new ArrayList<>();
            lines.add("read_mail：收件箱 " + address + "（" + unread + " 封未读）：");
            int index = 1;
            for (MailMessage message : messages) {
                lines.add("  " + index + ". " + message.preview());
                index++;
            }
            return ToolResult.ok(String.join("\n", lines));
        }
    }

    private final class OpenMailTool implements Tool {
        @Override
        public ToolSpec spec() {
            return new ToolSpec("open_mail",
                    "打开一封邮件查看全文（会自动标记为已读）；id 来自 read_mail 的列表。",
                    JsonSchema.object()
                            .string("id", "邮件 id（read_mail 返回）。")
                            .required("id"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            // 兼容 master 的 message_id 命名，但对外只声明 id。
            String raw = arguments.stringOr("id", "").trim();
            if (raw.isEmpty()) {
                raw = arguments.stringOr("message_id", "").trim();
            }
            if (raw.isEmpty()) {
                return ToolResult.error("open_mail：缺少邮件 id。");
            }
            RoleSpec spec = caller(agent);
            String address = mail.addressOf(spec);
            Optional<MailMessage> found = mail.read(address, new MailId(raw));
            if (found.isEmpty()) {
                return ToolResult.error("open_mail：未找到邮件 " + raw + "，请先调用 read_mail 查看当前收件箱。");
            }
            return ToolResult.ok(fullText(found.get()));
        }
    }

    private final class AddressBookTool implements Tool {
        @Override
        public ToolSpec spec() {
            return new ToolSpec("mail_address_book",
                    "查看公司通讯录：按组列出全部成员的姓名、邮箱与岗位；不确定收件人时先调用它。",
                    JsonSchema.object()
                            .string("keyword", "（可选）按关键词过滤：匹配姓名、用户名、职务、组名、职责或技能。"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String keyword = arguments.stringOr("keyword", "").trim().toLowerCase(Locale.ROOT);
            List<RoleSpec> specs = directory.specs();
            Map<String, List<RoleSpec>> byGroup = new TreeMap<>();
            int matched = 0;
            for (RoleSpec spec : specs) {
                if (!keyword.isEmpty() && !matches(spec, keyword)) {
                    continue;
                }
                matched++;
                String group = Text.isBlank(spec.group()) ? "未分组" : spec.group().trim();
                byGroup.computeIfAbsent(group, k -> new ArrayList<>()).add(spec);
            }
            if (matched == 0) {
                return ToolResult.ok("mail_address_book：没有匹配 \"" + keyword + "\" 的成员。");
            }
            String suffix = suffixOf(specs);
            List<String> lines = new ArrayList<>();
            lines.add("mail_address_book：公司通讯录（邮箱后缀 @" + suffix + "，共 " + matched + " 人）：");
            for (Map.Entry<String, List<RoleSpec>> entry : byGroup.entrySet()) {
                lines.add("[" + entry.getKey() + "]");
                entry.getValue().sort((a, b) -> Text.orEmpty(a.name()).compareTo(Text.orEmpty(b.name())));
                for (RoleSpec spec : entry.getValue()) {
                    String desc = !Text.isBlank(spec.title()) ? spec.title()
                            : (!Text.isBlank(spec.responsibilities()) ? spec.responsibilities() : "团队成员");
                    lines.add("  - " + spec.name() + " <" + mail.addressOf(spec) + "> — " + desc);
                }
            }
            return ToolResult.ok(String.join("\n", lines));
        }
    }

    // ── 解析辅助 ───────────────────────────────────────────────

    private RoleSpec caller(RoleId roleId) {
        return directory.spec(roleId).orElseThrow(() ->
                new DomainError("email.role.unknown", "当前角色不存在于花名册: " + roleId.value()));
    }

    /** 按逗号（中英文）切分，逐项解析为邮箱；解析失败的项收集进 {@code unresolved}。 */
    private List<String> resolveRecipients(String raw, List<String> unresolved) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String token : raw.split("[,，]")) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            String address = resolveOne(value);
            if (address.isEmpty()) {
                unresolved.add(value);
                continue;
            }
            if (seen.add(address.toLowerCase(Locale.ROOT))) {
                out.add(address);
            }
        }
        return out;
    }

    /** 完整邮箱原样返回；否则先按姓名、再按用户名反查公司邮箱。 */
    private String resolveOne(String value) {
        if (value.contains("@")) {
            return value;
        }
        for (RoleSpec spec : directory.specs()) {
            if (value.equals(spec.name())) {
                return mail.addressOf(spec);
            }
        }
        for (RoleSpec spec : directory.specs()) {
            if (!Text.isBlank(spec.username()) && value.equalsIgnoreCase(spec.username())) {
                return mail.addressOf(spec);
            }
        }
        return "";
    }

    private boolean matches(RoleSpec spec, String keyword) {
        String haystack = String.join(" ",
                Text.orEmpty(spec.name()), Text.orEmpty(spec.username()), Text.orEmpty(spec.title()),
                Text.orEmpty(spec.group()), Text.orEmpty(spec.responsibilities()),
                String.join(" ", spec.skills() == null ? List.of() : spec.skills()))
                .toLowerCase(Locale.ROOT);
        return haystack.contains(keyword);
    }

    private String suffixOf(List<RoleSpec> specs) {
        for (RoleSpec spec : specs) {
            String address = mail.addressOf(spec);
            int at = address.lastIndexOf('@');
            if (at >= 0) {
                return address.substring(at + 1);
            }
        }
        return "";
    }

    private String fullText(MailMessage message) {
        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(message.fromName())
                .append(" <").append(message.fromEmail()).append(">\n");
        sb.append("To: ").append(String.join(", ", message.to())).append("\n");
        if (message.cc() != null && !message.cc().isEmpty()) {
            sb.append("CC: ").append(String.join(", ", message.cc())).append("\n");
        }
        sb.append("Time: ").append(TIME.format(message.sentAt().atZone(ZoneId.systemDefault()))).append("\n");
        sb.append("Subject: ").append(message.subject()).append("\n");
        sb.append("─".repeat(40)).append("\n");
        sb.append(message.body());
        return sb.toString();
    }
}
