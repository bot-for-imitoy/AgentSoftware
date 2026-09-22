package com.agent.software.services;

/** 邮件配置：本轮只用虚拟邮箱，SMTP 字段先占位。 */
public final class MailConfig {

    public String mode = "virtual";
    public String domain = "agentsoftware.local";
    public String smtpHost = "";
    public int smtpPort = 587;
    public String smtpUser = "";
    public String smtpPassword = "";
    public String clientAddress = "client@agentsoftware.local";

    public static MailConfig fromEnv() {
        MailConfig c = new MailConfig();
        c.mode = System.getenv().getOrDefault("AGENTSOFTWARE_MAIL_MODE", "virtual");
        c.domain = System.getenv().getOrDefault("AGENTSOFTWARE_MAIL_DOMAIN", c.domain);
        c.smtpHost = System.getenv().getOrDefault("AGENTSOFTWARE_SMTP_HOST", "");
        String port = System.getenv("AGENTSOFTWARE_SMTP_PORT");
        if (port != null && !port.isBlank()) {
            try {
                c.smtpPort = Integer.parseInt(port.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        c.smtpUser = System.getenv().getOrDefault("AGENTSOFTWARE_SMTP_USER", "");
        c.smtpPassword = System.getenv().getOrDefault("AGENTSOFTWARE_SMTP_PASSWORD", "");
        c.clientAddress = System.getenv().getOrDefault("AGENTSOFTWARE_CLIENT_EMAIL", c.clientAddress);
        return c;
    }
}
