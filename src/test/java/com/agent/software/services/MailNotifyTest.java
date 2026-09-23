package com.agent.software.services;

import com.agent.software.AgentSystem;
import com.agent.software.io.WebInput;
import com.agent.software.role.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 邮件收件人解析 + NEW_MAIL 唤醒：role_id 与显示名都必须能把人叫醒。 */
class MailNotifyTest {

    @Test
    void sendingByRoleIdOrDisplayNameWakesTheRecipient(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            Role coo = system.getRolePool().find("COO");
            coo.stop();   // 停 worker，事件只入队，便于断言

            assertTrue(ceo.invokeTool("send_email",
                    Map.of("to", "COO", "subject", "s1", "body", "b1")).ok);
            assertEquals(1, coo.queueDepth(), "role_id 收件人应触发 NEW_MAIL");

            // 显示名（此前会拼成 "chen zong@..." 假地址，邮件丢失且不唤醒）
            assertTrue(ceo.invokeTool("send_email",
                    Map.of("to", "Chen Zong", "subject", "s2", "body", "b2")).ok);
            assertEquals(2, coo.queueDepth(), "显示名收件人应触发 NEW_MAIL");

            // 姓名片段
            assertTrue(ceo.invokeTool("send_email",
                    Map.of("to", "Chen", "subject", "s3", "body", "b3")).ok);
            assertEquals(3, coo.queueDepth(), "姓名片段应能解析");

            // 未知收件人必须报错，而不是静默成功
            var bad = ceo.invokeTool("send_email", Map.of("to", "Nobody", "subject", "x", "body", "y"));
            assertFalse(bad.ok, bad.text);
            assertTrue(bad.text.contains("cannot resolve"), bad.text);
            assertEquals(3, coo.queueDepth(), "解析失败不应产生任何投递");
        } finally {
            system.stop();
        }
    }

    @Test
    void mailToClientAddressIsAccepted(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            var res = ceo.invokeTool("send_email",
                    Map.of("to", "client", "subject", "hello", "body", "requirements?"));
            assertTrue(res.ok, res.text);
            assertEquals(1, system.getMailService().unreadCount(system.getMailService().getClientAddress()));
        } finally {
            system.stop();
        }
    }
}
