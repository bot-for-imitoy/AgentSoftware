package com.agent.software.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 虚拟邮箱：进程内保存每个地址的收件箱（后续接数据库/文件持久化）。
 */
public class VirtualMailService extends MailService {

    private static final Logger logger = LoggerFactory.getLogger(VirtualMailService.class);

    private final MailConfig config;
    private final Path dataDir;
    private final Map<String, List<MailMessage>> boxes = new ConcurrentHashMap<>();

    public VirtualMailService(MailConfig config, Path dataDir) {
        this.config = config == null ? MailConfig.fromEnv() : config;
        this.dataDir = dataDir;
    }

    @Override
    public String getAddress(String roleId) {
        String id = roleId == null || roleId.isBlank() ? "unknown" : roleId;
        return id.toLowerCase() + "@" + config.domain;
    }

    @Override
    public String getClientAddress() {
        return config.clientAddress;
    }

    @Override
    public String send(String from, List<String> to, List<String> cc, String subject, String body) {
        if (to == null || to.isEmpty()) {
            return "mail error: no recipients";
        }
        MailMessage msg = new MailMessage(UUID.randomUUID().toString(), from, "", to, cc, subject, body);
        for (String recipient : to) {
            deliver(msg, recipient);
        }
        if (cc != null) {
            for (String recipient : cc) {
                deliver(msg, recipient);
            }
        }
        logger.info("Mail sent from {} to {} (subject: {})", from, to, subject);
        return "mail sent: " + to + " (subject: " + subject + ")";
    }

    private void deliver(MailMessage msg, String address) {
        boxes.computeIfAbsent(address.toLowerCase(), k -> new ArrayList<>()).add(msg);
        notifyDelivered(msg, address);
    }

    @Override
    public List<MailMessage> inbox(String address, Integer limit) {
        List<MailMessage> list = boxes.getOrDefault(address == null ? "" : address.toLowerCase(), List.of());
        if (limit == null || limit <= 0 || limit >= list.size()) {
            return List.copyOf(list);
        }
        return List.copyOf(list.subList(list.size() - limit, list.size()));
    }

    @Override
    public int unreadCount(String address) {
        int n = 0;
        for (MailMessage m : inbox(address, null)) {
            if (!m.read) {
                n++;
            }
        }
        return n;
    }

    @Override
    public MailMessage read(String address, String messageId) {
        for (MailMessage m : inbox(address, null)) {
            if (m.messageId.equals(messageId)) {
                m.read = true;
                return m;
            }
        }
        return null;
    }

    /** 诊断用：地址 → 邮件数。 */
    public Map<String, Integer> stats() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Map.Entry<String, List<MailMessage>> e : boxes.entrySet()) {
            m.put(e.getKey(), e.getValue().size());
        }
        return m;
    }
}
