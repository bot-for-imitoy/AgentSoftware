package com.maf.scheduler.services;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.utils.Json;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * 公司邮件系统核心 (MailService) — 员工邮件收发 (Python 版 mail_service.py).
 *
 * 虚拟邮箱 (默认): 邮件投递到内部邮箱, 按需持久化到 data/mail/mailboxes.json.
 * 真实邮箱: 配置 SMTP 环境变量后自动切换为真实发送 (jakarta.mail);
 * 同时仍把邮件副本投递到内部员工邮箱.
 */
public class MailService {

    private static final Logger logger = LoggerFactory.getLogger(MailService.class);

    public static final String DEFAULT_MAIL_SUFFIX = "company.com";
    public static final String DEFAULT_MAIL_DIR = "data/mail";
    public static final String MAILBOX_FILE = "mailboxes.json";

    // ── MailConfig ─────────────────────────────────────────────

    /** 邮件配置: 后缀 + SMTP 参数 (从环境变量读取, 也可编程构造). */
    public static final class MailConfig {
        public String suffix = DEFAULT_MAIL_SUFFIX;
        public String smtpHost = "";
        public int smtpPort = 587;
        public String smtpUser = "";
        public String smtpPassword = "";
        public String smtpFrom = "";
        public Boolean useSsl = null;
        public String dataDir = DEFAULT_MAIL_DIR;

        public static MailConfig fromEnv() {
            MailConfig c = new MailConfig();
            String suffix = System.getenv().getOrDefault("MAIL_SUFFIX", "").strip();
            c.suffix = suffix.isEmpty() ? DEFAULT_MAIL_SUFFIX : suffix;
            c.smtpHost = System.getenv().getOrDefault("SMTP_HOST", "").strip();
            try {
                c.smtpPort = Integer.parseInt(System.getenv().getOrDefault("SMTP_PORT", "587").strip());
            } catch (NumberFormatException e) {
                c.smtpPort = 587;
            }
            c.smtpUser = System.getenv().getOrDefault("SMTP_USER", "").strip();
            c.smtpPassword = System.getenv().getOrDefault("SMTP_PASSWORD", "");
            c.smtpFrom = System.getenv().getOrDefault("SMTP_FROM", "").strip();
            String sslRaw = System.getenv().getOrDefault("SMTP_USE_SSL", "").strip().toLowerCase();
            if (sslRaw.equals("1") || sslRaw.equals("true") || sslRaw.equals("yes") || sslRaw.equals("on")) {
                c.useSsl = true;
            } else if (sslRaw.equals("0") || sslRaw.equals("false") || sslRaw.equals("no") || sslRaw.equals("off")) {
                c.useSsl = false;
            }
            String dir = System.getenv().getOrDefault("MAIL_DATA_DIR", "").strip();
            c.dataDir = dir.isEmpty() ? DEFAULT_MAIL_DIR : dir;
            return c;
        }

        /** 投递方式: "smtp" = 真实发送 (已配置 SMTP), "virtual" = 虚拟实现. */
        public String mode() {
            return smtpHost.isEmpty() ? "virtual" : "smtp";
        }
    }

    // ── MailMessage ────────────────────────────────────────────

    /** 一封邮件. */
    public static final class MailMessage {
        public String messageId;
        public String senderEmail;
        public String senderName;
        public String subject;
        public String body;
        public List<String> recipients = new ArrayList<>();
        public List<String> cc = new ArrayList<>();
        public double timestamp;
        public boolean read = false;
        public boolean viaSmtp = false;

        public MailMessage(String messageId, String senderEmail, String senderName,
                           String subject, String body, List<String> recipients,
                           List<String> cc, double timestamp, boolean read, boolean viaSmtp) {
            this.messageId = messageId;
            this.senderEmail = senderEmail;
            this.senderName = senderName;
            this.subject = subject;
            this.body = body;
            this.recipients = recipients != null ? new ArrayList<>(recipients) : new ArrayList<>();
            this.cc = cc != null ? new ArrayList<>(cc) : new ArrayList<>();
            this.timestamp = timestamp;
            this.read = read;
            this.viaSmtp = viaSmtp;
        }

        public static MailMessage create(String senderEmail, String senderName,
                                         String subject, String body,
                                         List<String> recipients, List<String> cc) {
            return new MailMessage(UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                    senderEmail, senderName, subject, body, recipients, cc,
                    System.currentTimeMillis() / 1000.0, false, false);
        }

        public Map<String, Object> toDict() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("message_id", messageId);
            m.put("sender_email", senderEmail);
            m.put("sender_name", senderName);
            m.put("subject", subject);
            m.put("body", body);
            m.put("recipients", new ArrayList<>(recipients));
            m.put("cc", new ArrayList<>(cc));
            m.put("timestamp", timestamp);
            m.put("read", read);
            m.put("via_smtp", viaSmtp);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static MailMessage fromDict(Map<String, Object> d) {
            return new MailMessage(
                    Json.str(d, "message_id", ""),
                    Json.str(d, "sender_email", ""),
                    Json.str(d, "sender_name", ""),
                    Json.str(d, "subject", ""),
                    Json.str(d, "body", ""),
                    (List<String>) d.getOrDefault("recipients", new ArrayList<String>()),
                    (List<String>) d.getOrDefault("cc", new ArrayList<String>()),
                    Json.doubleVal(d, "timestamp", System.currentTimeMillis() / 1000.0),
                    Json.boolVal(d, "read", false),
                    Json.boolVal(d, "via_smtp", false));
        }

        /** 单行摘要 (read_mail 列表用). */
        public String preview() {
            String flag = read ? "已读" : "未读";
            String stamp = new SimpleDateFormat("MM-dd HH:mm").format(new Date((long) (timestamp * 1000)));
            String bodyPreview = body != null && body.length() > 60 ? body.substring(0, 60) : body;
            return "[" + flag + "] " + senderName + " <" + senderEmail + "> 「" + subject + "」 "
                    + bodyPreview + " (" + stamp + ", id=" + messageId + ")";
        }

        /** 完整邮件内容 (open_mail 用). */
        public String fullText() {
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date((long) (timestamp * 1000)));
            String toLine = String.join(", ", recipients);
            String ccLine = cc.isEmpty() ? "" : "抄送: " + String.join(", ", cc);
            String smtpNote = viaSmtp ? " [已通过 SMTP 真实发送]" : "";
            StringBuilder sb = new StringBuilder();
            sb.append("发件人: ").append(senderName).append(" <").append(senderEmail).append(">").append(smtpNote).append("\n");
            sb.append("收件人: ").append(toLine).append("\n");
            if (!ccLine.isEmpty()) {
                sb.append(ccLine).append("\n");
            }
            sb.append("时间: ").append(stamp).append("\n");
            sb.append("主题: ").append(subject).append("\n");
            sb.append("─".repeat(40)).append("\n");
            sb.append(body);
            return sb.toString();
        }
    }

    // ── MailService ────────────────────────────────────────────

    public final MailConfig config;
    private final String dataDir;
    private final Map<String, List<MailMessage>> mailboxes = new LinkedHashMap<>();
    private final Object lock = new Object();
    private boolean loaded = false;

    public MailService(MailConfig config, String dataDir) {
        this.config = config != null ? config : MailConfig.fromEnv();
        this.dataDir = dataDir != null ? dataDir : this.config.dataDir;
    }

    public MailService() {
        this(null, null);
    }

    // ── 邮箱地址分配 ──────────────────────────────────────

    /** 角色 → 公司邮箱地址 (后缀由用户定义, 默认 company.com). */
    public String emailFor(AgentRole role) {
        String explicit = role.email == null ? "" : role.email.strip();
        if (!explicit.isEmpty()) {
            return explicit;
        }
        String username = role.username == null ? "" : role.username.strip();
        String local = username.isEmpty() ? (role.roleId == null || role.roleId.isEmpty() ? "agent" : role.roleId) : username;
        return (local + "@" + config.suffix).toLowerCase();
    }

    // ── 发送 ──────────────────────────────────────────────

    /**
     * 发送一封邮件. 虚拟模式: 投递到每个收件人邮箱; SMTP 模式: 先真实发送,
     * 成功后把副本投递到内部收件人邮箱. 返回发送结果摘要.
     */
    public String send(String senderEmail, String senderName, List<String> to,
                       String subject, String body, List<String> cc) {
        List<String> toList = new ArrayList<>();
        if (to != null) {
            for (String t : to) {
                if (t != null && !t.isEmpty()) {
                    toList.add(t);
                }
            }
        }
        List<String> ccList = new ArrayList<>();
        if (cc != null) {
            for (String t : cc) {
                if (t != null && !t.isEmpty()) {
                    ccList.add(t);
                }
            }
        }
        if (toList.isEmpty()) {
            return "错误: 收件人列表为空, 邮件未发送.";
        }
        if ((subject == null || subject.strip().isEmpty())
                && (body == null || body.strip().isEmpty())) {
            return "错误: 主题与正文不能同时为空, 邮件未发送.";
        }
        MailMessage msg = MailMessage.create(senderEmail, senderName,
                subject == null ? "" : subject, body == null ? "" : body, toList, ccList);
        if ("smtp".equals(config.mode())) {
            try {
                sendViaSmtp(msg);
            } catch (Exception exc) {
                logger.error("SMTP 发送失败: {}", exc.getMessage());
                return "错误: SMTP 发送失败: " + exc.getMessage() + " (邮件未投递)";
            }
            msg.viaSmtp = true;
        }
        // 虚拟投递 (SMTP 模式也投递内部副本, 供角色在模拟内网阅读)
        synchronized (lock) {
            load();
            List<String> all = new ArrayList<>(toList);
            all.addAll(ccList);
            for (String addr : all) {
                String key = addr.toLowerCase();
                mailboxes.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(MailMessage.fromDict(msg.toDict()));  // 每邮箱独立副本
            }
            save();
        }
        String recipientsDesc = String.join(", ", toList);
        String way = msg.viaSmtp ? "已通过 SMTP 真实发送" : "虚拟邮箱投递";
        return "邮件已发送给 " + recipientsDesc + ", 主题「" + msg.subject + "」, " + way + ".";
    }

    /** 用 jakarta.mail 真实发送邮件 (需已配置 SMTP_HOST). */
    private void sendViaSmtp(MailMessage msg) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.smtpHost);
        props.put("mail.smtp.port", String.valueOf(config.smtpPort));
        props.put("mail.smtp.connectiontimeout", "30000");
        props.put("mail.smtp.timeout", "30000");
        boolean useSsl = config.useSsl != null ? config.useSsl : (config.smtpPort == 465);
        if (useSsl) {
            props.put("mail.smtp.ssl.enable", "true");
        } else if (config.smtpPort == 587 || Boolean.FALSE.equals(config.useSsl)) {
            props.put("mail.smtp.starttls.enable", "true");
        }
        Session session = Session.getInstance(props);
        MimeMessage mime;
        try {
            mime = new MimeMessage(session);
            mime.setFrom(new InternetAddress(msg.senderEmail, msg.senderName, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new MessagingException("发件人编码失败: " + e.getMessage(), e);
        }
        try {
            mime.setSubject(msg.subject, "UTF-8");
            mime.setText(msg.body, "UTF-8");
        } catch (MessagingException e) {
            throw e;
        }
        for (String r : msg.recipients) {
            mime.addRecipient(Message.RecipientType.TO, new InternetAddress(r));
        }
        for (String c : msg.cc) {
            mime.addRecipient(Message.RecipientType.CC, new InternetAddress(c));
        }
        String fromAddr = !config.smtpFrom.isEmpty() ? config.smtpFrom
                : (!config.smtpUser.isEmpty() ? config.smtpUser : msg.senderEmail);
        if (!config.smtpUser.isEmpty()) {
            Transport.send(mime, config.smtpUser, config.smtpPassword);
        } else {
            Transport.send(mime);
        }
    }

    // ── 收件 ──────────────────────────────────────────────

    /** 读取某个邮箱的收件列表 (新到优先, limit 截取). */
    public List<MailMessage> inbox(String email, Integer limit) {
        synchronized (lock) {
            load();
            List<MailMessage> msgs = new ArrayList<>(mailboxes.getOrDefault(email.toLowerCase(), new ArrayList<>()));
            msgs.sort((a, b) -> Double.compare(b.timestamp, a.timestamp));
            if (limit != null && msgs.size() > limit) {
                msgs = new ArrayList<>(msgs.subList(0, limit));
            }
            return msgs;
        }
    }

    /** 未读邮件数. */
    public int unreadCount(String email) {
        synchronized (lock) {
            load();
            int n = 0;
            for (MailMessage m : mailboxes.getOrDefault(email.toLowerCase(), new ArrayList<>())) {
                if (!m.read) {
                    n++;
                }
            }
            return n;
        }
    }

    /** 打开一封邮件 (标记为已读). 不存在返回 null. */
    public MailMessage read(String email, String messageId) {
        synchronized (lock) {
            load();
            List<MailMessage> box = mailboxes.get(email.toLowerCase());
            if (box == null) {
                return null;
            }
            for (MailMessage m : box) {
                if (m.messageId.equals(messageId)) {
                    if (!m.read) {
                        m.read = true;
                        save();
                    }
                    return m;
                }
            }
            return null;
        }
    }

    /** 当前投递方式描述. */
    public String describe() {
        if ("smtp".equals(config.mode())) {
            return "真实邮件 (SMTP: " + config.smtpHost + ":" + config.smtpPort + ")";
        }
        return "虚拟邮箱 (未配置 SMTP, 邮件仅内部投递)";
    }

    // ── 持久化 (data/mail/mailboxes.json) ─────────────────

    private Path path() {
        return Paths.get(dataDir).resolve(MAILBOX_FILE);
    }

    /** 从磁盘加载邮箱 (幂等; 仅在首次访问时读取). */
    @SuppressWarnings("unchecked")
    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(path())) {
            return;
        }
        try {
            Map<String, Object> data = Json.parseObject(Files.readString(path()));
            Object boxes = data.get("mailboxes");
            if (boxes instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) boxes).entrySet()) {
                    List<MailMessage> msgs = new ArrayList<>();
                    if (e.getValue() instanceof List) {
                        for (Object m : (List<Object>) e.getValue()) {
                            if (m instanceof Map) {
                                msgs.add(MailMessage.fromDict((Map<String, Object>) m));
                            }
                        }
                    }
                    mailboxes.put(e.getKey(), msgs);
                }
            }
        } catch (Exception e) {
            logger.warn("邮箱持久化读取失败, 从空邮箱开始: {}", e.getMessage());
        }
    }

    /** 邮箱原子写盘 (tmp + rename). */
    private void save() {
        try {
            Map<String, Object> boxes = new LinkedHashMap<>();
            for (Map.Entry<String, List<MailMessage>> e : mailboxes.entrySet()) {
                List<Map<String, Object>> msgs = new ArrayList<>();
                for (MailMessage m : e.getValue()) {
                    msgs.add(m.toDict());
                }
                boxes.put(e.getKey(), msgs);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mailboxes", boxes);
            Json.atomicWrite(path(), Json.stringifyPretty(data));
        } catch (IOException e) {
            logger.warn("邮箱持久化写盘失败: {}", e.getMessage());
        }
    }

    // ── 全局共享实例 (懒加载单例) ────────────────────────────

    private static volatile MailService mailService;

    /** 获取进程级共享 MailService (懒加载, 配置读环境变量). */
    public static MailService getMailService() {
        if (mailService == null) {
            synchronized (MailService.class) {
                if (mailService == null) {
                    mailService = new MailService();
                }
            }
        }
        return mailService;
    }

    /** 测试用: 重置全局实例. */
    public static void resetInstance() {
        synchronized (MailService.class) {
            mailService = null;
        }
    }
}
