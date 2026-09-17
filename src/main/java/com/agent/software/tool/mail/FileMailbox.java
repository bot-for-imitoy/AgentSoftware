package com.agent.software.tool.mail;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.MailId;
import com.agent.software.kernel.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import com.agent.software.infra.config.AppConfig.Mail;
import com.agent.software.tool.mail.Mailbox.DeliveryListener;
import com.agent.software.tool.mail.Mailbox.OutgoingMail;

/**
 * 落盘的公司虚拟邮箱：地址由配置后缀推导，邮件按收件人存放于数据目录。
 *
 * <p>磁盘形状：每个地址一个文件 {@code data/mail/<安全文件名>.json}，内容 {@code {"messages":[...]}}，
 * 写入一律走 {@code .tmp + move} 的原子替换，避免进程崩溃留下半截收件箱。
 * 整个实例用一把对象锁串行化读写（按文件加锁在配置规模下收益有限，反而容易产生跨地址死锁）。
 *
 * <p>地址是唯一权威推导：{@link #addressOf(RoleSpec)} 之外没有任何地方再拼一次邮箱，
 * 修掉 master 中 {@code MailService.emailFor} 与各 adapter 各算一份的问题。
 */
public final class FileMailbox implements Mailbox {

    private static final Logger logger = LoggerFactory.getLogger(FileMailbox.class);

    /** 配置未给后缀时的兜底域名。 */
    static final String DEFAULT_SUFFIX = "agentsoftware.local";

    private final AppConfig.Mail config;
    private final AppPaths paths;
    private final String suffix;
    private final JacksonJsonCodec json = new JacksonJsonCodec();
    private final List<DeliveryListener> listeners = new CopyOnWriteArrayList<>();

    /** 整表锁：收件箱文件的读-改-写必须互斥。 */
    private final Object lock = new Object();

    /** 配置了真实 SMTP 时的外发器，否则为 null。 */
    private final SmtpSender smtp;

    /** 绑定邮件配置与数据路径。 */
    public FileMailbox(AppConfig.Mail config, AppPaths paths) {
        this.config = config == null ? AppConfig.defaults().mail() : config;
        this.paths = Objects.requireNonNull(paths, "paths");
        this.suffix = normalizeSuffix(this.config.suffix());
        this.smtp = this.config.smtp() != null && this.config.smtp().configured()
                ? new SmtpSender(this.config.smtp())
                : null;
    }

    // ── 地址 ───────────────────────────────────────────────────

    @Override
    public String addressOf(RoleSpec spec) {
        if (spec == null) {
            throw new DomainError("mail.role.null", "角色为空，无法推导邮箱地址");
        }
        String username = Text.isBlank(spec.username()) ? spec.id().value() : spec.username().trim();
        return username + "@" + suffix;
    }

    private static String normalizeSuffix(String raw) {
        String s = Text.orEmpty(raw).trim();
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        return s.isEmpty() ? DEFAULT_SUFFIX : s;
    }

    /** 本公司的域名（小写），用于判断收件人是否属于对外地址。 */
    private String companyDomain() {
        return suffix.toLowerCase(Locale.ROOT);
    }

    private static boolean isExternal(String address) {
        int at = address == null ? -1 : address.lastIndexOf('@');
        return at >= 0 && at < address.length() - 1;
    }

    private boolean belongsToCompany(String address) {
        if (!isExternal(address)) {
            return true;
        }
        String domain = address.substring(address.lastIndexOf('@') + 1).trim().toLowerCase(Locale.ROOT);
        return domain.equals(companyDomain());
    }

    // ── 发送 ───────────────────────────────────────────────────

    @Override
    public MailId send(OutgoingMail mail) {
        if (mail == null) {
            throw new DomainError("mail.no-recipient", "邮件为空，未发送");
        }
        List<String> to = clean(mail.to());
        List<String> cc = clean(mail.cc());
        List<String> recipients = distinct(concat(to, cc));
        if (recipients.isEmpty()) {
            throw new DomainError("mail.no-recipient", "收件人为空，邮件未发送");
        }
        String fromAddress = Text.orEmpty(mail.fromAddress()).trim();
        String fromName = Text.orEmpty(mail.fromName());
        String subject = Text.orEmpty(mail.subject());
        String body = Text.orEmpty(mail.body());

        MailId firstId = null;
        List<MailMessage> copies = new ArrayList<>(recipients.size());
        synchronized (lock) {
            Instant now = Instant.now();
            for (String recipient : recipients) {
                MailId id = MailId.generate();
                if (firstId == null) {
                    firstId = id;
                }
                MailMessage message = new MailMessage(id, fromAddress, fromName,
                        List.copyOf(to), List.copyOf(cc), subject, body, now, false);
                String key = storageKey(recipient);
                List<MailMessage> box = loadMessages(key);
                box.add(message);
                saveMessages(key, box);
                copies.add(message);
            }
        }

        // 投递通知在锁外触发：监听器可能会反过来读邮箱/发事件，不能持有文件锁。
        for (int i = 0; i < recipients.size(); i++) {
            MailMessage message = copies.get(i);
            String recipient = recipients.get(i);
            for (DeliveryListener listener : listeners) {
                try {
                    listener.delivered(message, recipient);
                } catch (RuntimeException e) {
                    logger.warn("邮件投递监听器失败（收件人 {}）: {}", recipient, e.getMessage());
                }
            }
        }

        sendExternalIfNeeded(fromAddress, fromName, to, cc, subject, body);
        return firstId;
    }

    /**
     * 对外发送：仅当配置了 SMTP 且存在不属于本公司域名的收件人时，把外部收件人单独走一次
     * {@link SmtpSender}。内部邮箱始终先落盘成功；外发失败只记日志，不回滚内部投递
     * （对齐 master "SMTP 失败则整封不投" 的反面取舍，见报告）。
     */
    private void sendExternalIfNeeded(String fromAddress, String fromName,
                                      List<String> to, List<String> cc,
                                      String subject, String body) {
        if (smtp == null) {
            return;
        }
        List<String> externalTo = to.stream().filter(a -> !belongsToCompany(a)).toList();
        List<String> externalCc = cc.stream().filter(a -> !belongsToCompany(a)).toList();
        if (externalTo.isEmpty() && externalCc.isEmpty()) {
            return;
        }
        try {
            smtp.send(new OutgoingMail(fromAddress, fromName, externalTo, externalCc, subject, body));
        } catch (RuntimeException e) {
            logger.warn("对外 SMTP 发送失败（内部邮箱已投递）: {}", e.getMessage());
        }
    }

    // ── 收件 ───────────────────────────────────────────────────

    @Override
    public List<MailMessage> inbox(String address, int limit) {
        if (Text.isBlank(address)) {
            return List.of();
        }
        synchronized (lock) {
            List<MailMessage> box = loadMessages(storageKey(address));
            box.sort(Comparator.comparing(MailMessage::sentAt).reversed());
            if (limit > 0 && box.size() > limit) {
                box = new ArrayList<>(box.subList(0, limit));
            }
            return List.copyOf(box);
        }
    }

    @Override
    public int unreadCount(String address) {
        if (Text.isBlank(address)) {
            return 0;
        }
        synchronized (lock) {
            int count = 0;
            for (MailMessage message : loadMessages(storageKey(address))) {
                if (!message.read()) {
                    count++;
                }
            }
            return count;
        }
    }

    @Override
    public Optional<MailMessage> read(String address, MailId id) {
        if (Text.isBlank(address) || id == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            String key = storageKey(address);
            List<MailMessage> box = loadMessages(key);
            for (int i = 0; i < box.size(); i++) {
                MailMessage message = box.get(i);
                if (message.id().equals(id)) {
                    if (message.read()) {
                        return Optional.of(message);
                    }
                    MailMessage opened = new MailMessage(message.id(), message.fromEmail(), message.fromName(),
                            message.to(), message.cc(), message.subject(), message.body(),
                            message.sentAt(), true);
                    box.set(i, opened);
                    saveMessages(key, box);
                    return Optional.of(opened);
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public void onDelivery(DeliveryListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    // ── 持久化 ─────────────────────────────────────────────────

    /** 每个地址一个 JSON 文件；{@code @} → {@code _at_}，其余非法字符 → {@code _}。 */
    private Path pathFor(String key) {
        String safe = key.toLowerCase(Locale.ROOT).replace("@", "_at_").replaceAll("[^a-z0-9._-]", "_");
        return paths.dataFile("mail", safe + ".json");
    }

    private static String storageKey(String address) {
        return Text.orEmpty(address).trim().toLowerCase(Locale.ROOT);
    }

    private List<MailMessage> loadMessages(String key) {
        Path path = pathFor(key);
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try {
            Map<String, Object> root = json.tryReadMap(Files.readString(path, StandardCharsets.UTF_8));
            Object raw = root.get("messages");
            List<MailMessage> box = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        box.add(fromMap(castMap(map)));
                    }
                }
            }
            return box;
        } catch (IOException e) {
            logger.warn("读取收件箱失败，按空邮箱继续: {} ({})", path, e.getMessage());
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** 原子写入：先写同目录 .tmp，再 move 覆盖，避免半截文件。 */
    private void saveMessages(String key, List<MailMessage> box) {
        Path target = pathFor(key);
        paths.ensure(target);
        List<Map<String, Object>> messages = new ArrayList<>(box.size());
        for (MailMessage message : box) {
            messages.add(toMap(message));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("messages", messages);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, json.writePretty(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new DomainError("mail.persist.failed", "写入收件箱失败: " + target, e);
        }
    }

    private static Map<String, Object> toMap(MailMessage message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", message.id().value());
        m.put("fromEmail", Text.orEmpty(message.fromEmail()));
        m.put("fromName", Text.orEmpty(message.fromName()));
        m.put("to", new ArrayList<>(message.to()));
        m.put("cc", new ArrayList<>(message.cc()));
        m.put("subject", Text.orEmpty(message.subject()));
        m.put("body", Text.orEmpty(message.body()));
        m.put("sentAt", message.sentAt().toEpochMilli() / 1000.0);
        m.put("read", message.read());
        return m;
    }

    private static MailMessage fromMap(Map<String, Object> m) {
        String id = string(m, "id", "");
        return new MailMessage(
                Text.isBlank(id) ? MailId.generate() : new MailId(id),
                string(m, "fromEmail", ""),
                string(m, "fromName", ""),
                stringList(m.get("to")),
                stringList(m.get("cc")),
                string(m, "subject", ""),
                string(m, "body", ""),
                instant(m.get("sentAt")),
                bool(m.get("read"), false));
    }

    private static String string(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        }
        return out;
    }

    private static Instant instant(Object raw) {
        if (raw instanceof Number n) {
            return Instant.ofEpochMilli((long) (n.doubleValue() * 1000));
        }
        return Instant.now();
    }

    private static boolean bool(Object raw, boolean fallback) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw == null) {
            return fallback;
        }
        return switch (String.valueOf(raw).trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> fallback;
        };
    }

    // ── 小工具 ─────────────────────────────────────────────────

    private static List<String> clean(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                if (!Text.isBlank(v)) {
                    out.add(v.trim());
                }
            }
        }
        return out;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** 按地址去重（大小写不敏感），保留首次出现的顺序：同一人既在 to 又在 cc 只落一封。 */
    private static List<String> distinct(List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (seen.add(v.toLowerCase(Locale.ROOT))) {
                out.add(v);
            }
        }
        return out;
    }
}
