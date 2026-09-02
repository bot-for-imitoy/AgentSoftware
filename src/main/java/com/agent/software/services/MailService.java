package com.agent.software.services;

import com.agent.software.role.AgentRole;
import com.agent.software.utils.Json;
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
 * Core company mail system (MailService) — employee email send/receive (Python version mail_service.py).
 *
 * Virtual mailbox (default): mail is delivered to internal mailboxes, persisted on demand to data/mail/mailboxes.json.
 * Real mailbox: after configuring the SMTP environment variables, automatically switches to real sending (jakarta.mail);
 * it also still delivers a copy of the mail to the internal employee mailbox.
 */
public class MailService {

    private static final Logger logger = LoggerFactory.getLogger(MailService.class);

    public static final String DEFAULT_MAIL_SUFFIX = "company.com";
    public static final String DEFAULT_MAIL_DIR = "data/mail";
    public static final String MAILBOX_FILE = "mailboxes.json";

    // ── MailConfig ─────────────────────────────────────────────

    /** Mail configuration: suffix + SMTP parameters (read from environment variables, or constructed programmatically). */
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

        /** Delivery mode: "smtp" = real sending (SMTP configured), "virtual" = virtual implementation. */
        public String mode() {
            return smtpHost.isEmpty() ? "virtual" : "smtp";
        }
    }

    // ── MailMessage ────────────────────────────────────────────

    /** A single email. */
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

        /** One-line summary (for the read_mail list). */
        public String preview() {
            String flag = read ? "read" : "unread";
            String stamp = new SimpleDateFormat("MM-dd HH:mm").format(new Date((long) (timestamp * 1000)));
            String bodyPreview = body != null && body.length() > 60 ? body.substring(0, 60) : body;
            return "[" + flag + "] " + senderName + " <" + senderEmail + "> '" + subject + "' "
                    + bodyPreview + " (" + stamp + ", id=" + messageId + ")";
        }

        /** Full mail content (for open_mail). */
        public String fullText() {
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date((long) (timestamp * 1000)));
            String toLine = String.join(", ", recipients);
            String ccLine = cc.isEmpty() ? "" : "CC: " + String.join(", ", cc);
            String smtpNote = viaSmtp ? " [sent via real SMTP]" : "";
            StringBuilder sb = new StringBuilder();
            sb.append("From: ").append(senderName).append(" <").append(senderEmail).append(">").append(smtpNote).append("\n");
            sb.append("To: ").append(toLine).append("\n");
            if (!ccLine.isEmpty()) {
                sb.append(ccLine).append("\n");
            }
            sb.append("Time: ").append(stamp).append("\n");
            sb.append("Subject: ").append(subject).append("\n");
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

    // ── Email address assignment ──────────────────────────────────────

    /** Role → company email address (suffix defined by the user, default company.com). */
    public String emailFor(AgentRole role) {
        String explicit = role.email == null ? "" : role.email.strip();
        if (!explicit.isEmpty()) {
            return explicit;
        }
        String username = role.username == null ? "" : role.username.strip();
        String local = username.isEmpty() ? (role.roleId == null || role.roleId.isEmpty() ? "agent" : role.roleId) : username;
        return (local + "@" + config.suffix).toLowerCase();
    }

    // ── Sending ──────────────────────────────────────────────

    /**
     * Send an email. Virtual mode: deliver to each recipient's mailbox; SMTP mode: send for real first,
     * then deliver a copy to the internal recipient mailbox on success. Returns a summary of the send result.
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
            return "Error: recipient list is empty, email not sent.";
        }
        if ((subject == null || subject.strip().isEmpty())
                && (body == null || body.strip().isEmpty())) {
            return "Error: subject and body cannot both be empty, email not sent.";
        }
        MailMessage msg = MailMessage.create(senderEmail, senderName,
                subject == null ? "" : subject, body == null ? "" : body, toList, ccList);
        if ("smtp".equals(config.mode())) {
            try {
                sendViaSmtp(msg);
            } catch (Exception exc) {
                logger.error("SMTP send failed: {}", exc.getMessage());
                return "Error: SMTP send failed: " + exc.getMessage() + " (email not delivered)";
            }
            msg.viaSmtp = true;
        }
        // Virtual delivery (SMTP mode also delivers an internal copy, for roles to read in the simulated intranet)
        synchronized (lock) {
            load();
            List<String> all = new ArrayList<>(toList);
            all.addAll(ccList);
            for (String addr : all) {
                String key = addr.toLowerCase();
                mailboxes.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(MailMessage.fromDict(msg.toDict()));  // Independent copy per mailbox
            }
            save();
        }
        String recipientsDesc = String.join(", ", toList);
        String way = msg.viaSmtp ? "sent via real SMTP" : "virtual mailbox delivery";
        return "Email sent to " + recipientsDesc + ", subject " + msg.subject + ", " + way + ".";
    }

    /** Send mail for real with jakarta.mail (SMTP_HOST must be configured). */
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
            throw new MessagingException("Failed to encode sender: " + e.getMessage(), e);
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

    // ── Receiving ──────────────────────────────────────────────

    /** Read the inbox list of a mailbox (newest first, truncated by limit). */
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

    /** Number of unread emails. */
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

    /** Open an email (marks it as read). Returns null if it does not exist. */
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

    /** Description of the current delivery mode. */
    public String describe() {
        if ("smtp".equals(config.mode())) {
            return "Real email (SMTP: " + config.smtpHost + ":" + config.smtpPort + ")";
        }
        return "Virtual mailbox (SMTP not configured, internal delivery only)";
    }

    // ── Persistence (data/mail/mailboxes.json) ─────────────────

    private Path path() {
        return Paths.get(dataDir).resolve(MAILBOX_FILE);
    }

    /** Load mailboxes from disk (idempotent; reads only on first access). */
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
            logger.warn("Failed to read mailbox persistence, starting with empty mailboxes: {}", e.getMessage());
        }
    }

    /** Atomically write mailboxes to disk (tmp + rename). */
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
            logger.warn("Failed to write mailbox persistence to disk: {}", e.getMessage());
        }
    }

    // ── Global shared instance (lazy-loaded singleton) ────────────────────────────

    private static volatile MailService mailService;

    /** Get the process-wide shared MailService (lazy-loaded, config read from environment variables). */
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

    /** For testing: reset the global instance. */
    public static void resetInstance() {
        synchronized (MailService.class) {
            mailService = null;
        }
    }
}
