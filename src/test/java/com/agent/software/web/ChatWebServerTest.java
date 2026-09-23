package com.agent.software.web;

import com.agent.software.AgentSystem;
import com.agent.software.io.WebInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

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
}
