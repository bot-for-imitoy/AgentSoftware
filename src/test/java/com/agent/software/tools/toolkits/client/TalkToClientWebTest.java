package com.agent.software.tools.toolkits.client;

import com.agent.software.AgentSystem;
import com.agent.software.role.AgentRole;
import com.agent.software.role.RoleLoader;
import com.agent.software.tools.Tool;
import com.agent.software.web.ChatStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * talk_to_client Web 模式集成测试: 领导调用 → 输入框启用状态 (等待甲方回复)
 * → 甲方在网页提交回复 → 领导拿到回复, 聊天记录完整.
 */
class TalkToClientWebTest {

    private static boolean waitUntil(java.util.function.Supplier<Boolean> pred, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(pred.get())) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    @Test
    void testWebModeRoundtrip() throws Exception {
        AgentSystem system = new AgentSystem(List.of(RoleLoader.getTemplate("CEO")), null, 30.0, false);
        AgentRole ceo = system.getRole("CEO");
        ChatStore store = system.context().chatStore;
        store.markAttached();   // 模拟 Web 前端已挂载

        Tool talkToClient = new Client(ceo).getTools().stream()
                .filter(t -> "talk_to_client".equals(t.getToolName()))
                .findFirst().orElseThrow();

        AtomicReference<String> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(talkToClient.handler(Map.of("message", "您好，请问项目需求是？"))));
        t.start();

        try {
            // 领导进入"等待甲方回复"状态 → Web 输入框应启用
            assertTrue(waitUntil(store::isClientWaitPending, 5000), "领导未进入等待甲方回复状态");
            assertEquals("CEO", store.pendingHolderRoleId());
            assertEquals("林总", store.pendingHolderName());

            // 甲方在网页上回复
            store.postClientReply("帮我开发一个支付系统");

            t.join(5000);
            assertFalse(t.isAlive());
            assertTrue(result.get().contains("client reply: 帮我开发一个支付系统"));
            assertFalse(store.isClientWaitPending(), "回复后等待状态应复位");

            // 聊天记录: 领导 → 甲方, 甲方 → 领导 (归属领导组)
            List<Map<String, Object>> msgs = store.messagesSince(0);
            assertEquals(2, msgs.size());
            assertEquals("林总", msgs.get(0).get("fromName"));
            assertEquals("甲方", msgs.get(0).get("toName"));
            assertEquals("甲方", msgs.get(1).get("fromName"));
            assertEquals("林总", msgs.get(1).get("toName"));
            assertEquals("Leadership Group", msgs.get(0).get("group"));
            assertEquals("Leadership Group", msgs.get(1).get("group"));
        } finally {
            t.join(1000);
            system.stop();
        }
    }
}
