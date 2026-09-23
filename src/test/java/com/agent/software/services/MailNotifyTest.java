package com.agent.software.services;

import com.agent.software.AgentSystem;
import com.agent.software.event.Event;
import com.agent.software.event.EventType;
import com.agent.software.event.Priority;
import com.agent.software.io.WebInput;
import com.agent.software.role.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 邮件收件人解析 + NEW_MAIL 唤醒 + 去重跳过。 */
class MailNotifyTest {

    @Test
    void sendingByRoleIdOrDisplayNameWakesTheRecipient(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            Role coo = system.getRolePool().find("COO");
            coo.setLlm(new FakeLlm());   // 任务瞬间结束，便于确定性统计"被唤醒几次"

            assertTrue(ceo.invokeTool("send_email",
                    Map.of("to", "COO", "subject", "s1", "body", "b1")).ok);
            assertTrue(ceo.invokeTool("send_email",
                    Map.of("to", "Chen Zong", "subject", "s2", "body", "b2")).ok, "显示名应收件人");
            assertTrue(ceo.invokeTool("send_email",
                    Map.of("to", "Chen", "subject", "s3", "body", "b3")).ok, "姓名片段应能解析");

            var mail = system.getMailService();
            assertEquals(3, mail.inbox(mail.getAddress("COO"), null).size());

            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && answered(coo) < 3) {
                Thread.sleep(20);
            }
            assertTrue(answered(coo) >= 3,
                    "3 条通知都应把 COO 唤醒（role_id / 显示名 / 姓名片段）: " + coo.readJournal());

            // 未知收件人必须报错，而不是静默成功
            var bad = ceo.invokeTool("send_email", Map.of("to", "Nobody", "subject", "x", "body", "y"));
            assertFalse(bad.ok, bad.text);
            assertTrue(bad.text.contains("cannot resolve"), bad.text);
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

    @Test
    void sameAddressInToAndCcNotifiesOnlyOnce(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            var res = ceo.invokeTool("send_email",
                    Map.of("to", "COO", "cc", "coo@agentsoftware.local", "subject", "S", "body", "B"));
            assertTrue(res.ok, res.text);
            var addr = system.getMailService().getAddress("COO");
            assertEquals(1, system.getMailService().inbox(addr, null).size(),
                    "同一邮箱不应重复入库/重复通知");
        } finally {
            system.stop();
        }
    }

    @Test
    void alreadyReadMailNotificationIsSkippedWithoutCallingTheLlm(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            Role coo = system.getRolePool().find("COO");
            coo.setLlm(new FakeLlm());

            assertTrue(ceo.invokeTool("send_email",
                    Map.of("to", "COO", "subject", "S", "body", "B")).ok);
            var mail = system.getMailService();
            var addr = mail.getAddress("COO");
            String messageId = mail.inbox(addr, 1).get(0).messageId;
            mail.read(addr, messageId);   // 模拟"邮件已被读过"（通知积压场景）

            system.getEventBus().post(Event.builder()
                    .to("COO").type(EventType.NEW_MAIL).priority(Priority.NORMAL).at(0)
                    .payload(Map.of("message_id", messageId)).content("duplicate notification")
                    .build());

            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline
                    && coo.readJournal().stream().noneMatch(l -> l.contains("Skip duplicate NEW_MAIL"))) {
                Thread.sleep(20);
            }
            assertTrue(coo.readJournal().stream().anyMatch(l -> l.contains("Skip duplicate NEW_MAIL")),
                    "已读邮件的重复通知应被静默跳过: " + coo.readJournal());
        } finally {
            system.stop();
        }
    }

    @Test
    void readMailUnreadOnlyFiltersOutReadMail(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            Role coo = system.getRolePool().find("COO");
            coo.setLlm(new FakeLlm());

            assertTrue(ceo.invokeTool("send_email",
                    Map.of("to", "COO", "subject", "READ_ME_SUBJECT", "body", "a")).ok);
            assertTrue(ceo.invokeTool("send_email",
                    Map.of("to", "COO", "subject", "UNREAD_SUBJECT", "body", "b")).ok);

            var mail = system.getMailService();
            var addr = mail.getAddress("COO");
            // 把较早那封标记为已读
            mail.read(addr, mail.inbox(addr, null).get(0).messageId);

            var unread = coo.invokeTool("read_mail", Map.of("unread_only", true));
            assertTrue(unread.ok, unread.text);
            assertTrue(unread.text.contains("UNREAD_SUBJECT"), unread.text);
            assertFalse(unread.text.contains("READ_ME_SUBJECT"), unread.text);

            var all = coo.invokeTool("read_mail", Map.of());
            assertTrue(all.text.contains("READ_ME_SUBJECT") && all.text.contains("UNREAD_SUBJECT"), all.text);
        } finally {
            system.stop();
        }
    }

    /** 角色已完成过多少个任务（每个任务收尾会写一条 answer 日志）。 */
    private static long answered(Role role) {
        return role.readJournal().stream().filter(l -> l.contains("answer(")).count();
    }

    /** 立即返回的假 LLM，避免测试里真的发 HTTP。 */
    private static final class FakeLlm extends com.agent.software.llm.LLM {
        @Override
        public String getModel() {
            return "fake";
        }

        @Override
        public String getEndpoint() {
            return "fake";
        }

        @Override
        public com.agent.software.llm.Response request() {
            return new com.agent.software.llm.Response("ok", "", List.of(), 0);
        }
    }
}
