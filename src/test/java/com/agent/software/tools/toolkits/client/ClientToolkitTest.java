package com.agent.software.tools.toolkits.client;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Toolkit;
import com.agent.software.tools.Toolkits;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 与甲方沟通工具测试: 领导组全员装配 + 全局互斥锁.
 */
class ClientToolkitTest {

    private static boolean hasTalkToClient(List<Toolkit> toolkits) {
        return toolkits.stream().anyMatch(tk -> tk.getTools().stream()
                .anyMatch(t -> "talk_to_client".equals(t.getToolName())));
    }

    // ── 装配: 领导组全员获得 talk_to_client ──────────────

    @Test
    void testLeadershipMembersGetClientToolkit() {
        for (String rid : new String[]{"CEO", "COO", "HR", "CTO", "business_analyst"}) {
            AgentRole leader = AgentRole.builder().name("领导").roleId(rid)
                    .group(Toolkits.LEADERSHIP_GROUP).build();
            assertTrue(hasTalkToClient(Toolkits.defaultToolkits(leader)),
                    rid + " (领导组) 应装配 talk_to_client");
        }
    }

    @Test
    void testNonLeadershipMembersDoNotGetClientToolkit() {
        AgentRole dev = AgentRole.builder().name("顾承宇").roleId("frontend_dev_1")
                .group("Frontend Development Group").build();
        assertFalse(hasTalkToClient(Toolkits.defaultToolkits(dev)),
                "非领导组成员不应装配 talk_to_client");

        AgentRole ungrouped = AgentRole.builder().name("新人").roleId("newbie_1").build();
        assertFalse(hasTalkToClient(Toolkits.defaultToolkits(ungrouped)),
                "未分组角色不应装配 talk_to_client");
    }

    // ── 互斥锁 ─────────────────────────────────────────────

    @Test
    void testClientLockMutualExclusion() {
        ClientCommunicationLock lock = new ClientCommunicationLock();
        // 空闲可获取
        assertNull(lock.tryAcquire("CEO", "林总"));
        assertTrue(lock.isHeld());
        // 他人占用 → 返回错误描述
        String conflict = lock.tryAcquire("COO", "陈总");
        assertTrue(conflict != null && conflict.contains("林总")
                && conflict.contains("currently talking to the client"));
        // 同一角色重复获取放行 (可重入, 不锁死自己)
        assertNull(lock.tryAcquire("CEO", "林总"));
        // 非持有者 release 无效
        lock.release("COO");
        assertTrue(lock.isHeld());
        // 持有者 release → 释放
        lock.release("CEO");
        assertFalse(lock.isHeld());
        // 释放后可被他人获取
        assertNull(lock.tryAcquire("COO", "陈总"));
        lock.release("COO");
    }

    // ── handler: 锁占用时立即返回错误 (不阻塞等待输入) ────

    @Test
    void testTalkToClientRejectedWhileHeld() {
        ClientCommunicationLock lock = new ClientCommunicationLock();
        AgentRole a = AgentRole.builder().name("林总").roleId("CEO").build();
        AgentRole b = AgentRole.builder().name("陈总").roleId("COO").build();
        TalkToClient ta = new TalkToClient(a, lock);
        TalkToClient tb = new TalkToClient(b, lock);

        // a 先占用锁 (模拟正在与甲方对话)
        assertNull(lock.tryAcquire("CEO", "林总"));
        // b 调用 → 锁占用 → 返回错误, 不进入 stdin 读取
        String r = tb.handler(Map.of("message", "hello"));
        assertTrue(r.contains("Error"));
        assertTrue(r.contains("currently talking to the client"));
        assertTrue(r.contains("林总"));
        // 释放后再次调用不再报锁冲突 (后续会进入 stdin 读取, 此处只验证锁已释放)
        lock.release("CEO");
        assertFalse(lock.isHeld());
    }
}
