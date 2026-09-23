package com.agent.software.web;

import com.agent.software.AgentSystem;
import com.agent.software.io.WebInput;
import com.agent.software.role.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Web 接口契约：与静态前端 app.js 的约定保持一致（state / messages / pause / resume）。 */
class ChatWebServerTest {

    private static String get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String post(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String postJson(HttpClient http, String url, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    /** 等角色的上下文里出现某条内容（事件被投递并被处理）。 */
    private static void awaitContext(Role role, String needle) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            for (var m : role.getContext().history()) {
                if (m.content != null && m.content.contains(needle)) {
                    return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError(role.roleId + " 没收到 " + needle + ": " + role.readJournal());
    }

    @Test
    void stateMessagesPauseResumeContract(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        ChatWebServer web = new ChatWebServer(system, "127.0.0.1", 0);
        try {
            web.start();
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + web.port();

            // 前端靠 ok 判断连接成功，缺了就会一直显示 Connecting…
            String state = get(http, base + "/api/state");
            assertTrue(state.contains("\"ok\":true"), state);
            assertTrue(state.contains("\"groups\""), state);
            assertTrue(state.contains("Leadership Group"), state);
            assertTrue(state.contains("\"clientTalk\""), state);

            // 消息必须是 camelCase + ok + lastSeq，否则前端渲染成 Unknown
            system.getChatStore().record("talk", "Leadership Group", "CEO", "Lin Zong",
                    "COO", "Chen Zong", "hello", "NORMAL");
            String messages = get(http, base + "/api/messages?since=0");
            assertTrue(messages.contains("\"ok\":true"), messages);
            assertTrue(messages.contains("\"lastSeq\""), messages);
            assertTrue(messages.contains("\"fromRoleId\":\"CEO\""), messages);
            assertTrue(messages.contains("\"fromName\":\"Lin Zong\""), messages);

            // 暂停/恢复按钮
            assertTrue(post(http, base + "/api/pause").contains("\"ok\":true"));
            assertTrue(system.getTimeBus().isPaused());
            assertTrue(post(http, base + "/api/resume").contains("\"ok\":true"));
            assertFalse(system.getTimeBus().isPaused());
        } finally {
            web.stop();
            system.stop();
        }
    }

    /**
     * 需求 B：甲方可以指定大组内任意成员「口头」或「邮件」沟通，两条路都要真的把角色唤醒。
     * 同时确认没进大组的人是拒收的。
     */
    @Test
    void clientCanTalkToAndEmailAnyCohortMember(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        ChatWebServer web = new ChatWebServer(system, "127.0.0.1", 0);
        try {
            web.start();
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + web.port();
            Role cto = system.getRolePool().find("CTO");
            cto.setLlm(new FakeLlm());   // 别真的去调 LLM

            // 口头：客户找 CTO → CTO 被唤醒并收到 [talk] 任务
            String talk = postJson(http, base + "/api/talk?role=CTO", "{\"text\":\"status please\"}");
            assertTrue(talk.contains("\"ok\":true"), talk);
            awaitContext(cto, "[talk] status please");

            // 邮件：以 client@ 身份发信给 CTO → 进收件箱 + NEW_MAIL 唤醒
            String mail = postJson(http, base + "/api/client_mail",
                    "{\"to\":\"CTO\",\"subject\":\"Spec\",\"text\":\"please review\"}");
            assertTrue(mail.contains("\"ok\":true"), mail);
            var mailbox = system.getMailService();
            String addr = mailbox.getAddress("CTO");
            assertEquals(1, mailbox.inbox(addr, null).size(), "客户的信应落到 CTO 收件箱");
            assertEquals(mailbox.getClientAddress(), mailbox.inbox(addr, null).get(0).senderEmail);
            awaitContext(cto, "[mail] New mail from");

            // 没进大组的人（“假死”）不允许寻址
            assertTrue(postJson(http, base + "/api/talk?role=architect", "{\"text\":\"hi\"}")
                    .contains("not in the current cohort"));
            assertTrue(postJson(http, base + "/api/client_mail", "{\"to\":\"architect\",\"text\":\"hi\"}")
                    .contains("not in the current cohort"));

            // 客户主动找过 CTO 之后，会话归 CTO；结束会话后通道空出来
            assertEquals("CTO", system.getClientChannel().getCurrentRoleId());
            assertTrue(post(http, base + "/api/client_end").contains("\"ok\":true"));
            assertTrue(system.getClientChannel().isFree());
        } finally {
            web.stop();
            system.stop();
        }
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
            return new com.agent.software.llm.Response("ok", "", java.util.List.of(), 0);
        }
    }
}
