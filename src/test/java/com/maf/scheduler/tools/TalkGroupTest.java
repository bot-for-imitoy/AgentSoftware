package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.RolePool;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.tools.toolkits.talk.ListRoles;
import com.maf.scheduler.tools.toolkits.talk.Talk;
import com.maf.scheduler.core.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * talk 工具组内交流限制测试 (Python 版 test_talk_group.py 的 Java 对应物).
 */
class TalkGroupTest {

    @TempDir
    Path tmp;

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

    private static AgentRole role(String name, String rid, String group) {
        return AgentRole.builder().name(name).roleId(rid).group(group).build();
    }

    private static Map<String, ToolKit> setupRoles(RolePool pool, AgentRole... roles) {
        Map<String, ToolKit> toolkits = new LinkedHashMap<>();
        for (AgentRole role : roles) {
            pool.addRole(role);
            role.setPool(pool);
            ToolKit tk = ToolkitBridge.toLegacy(new Talk(role, pool));
            toolkits.put(role.roleId, tk);
        }
        return toolkits;
    }

    private static String talk(Map<String, ToolKit> toolkits, String senderId,
                               String target, String message, boolean wait) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("target", target);
        args.put("message", message);
        if (wait) {
            args.put("wait", true);
        }
        return toolkits.get(senderId).getTool("talk").handler.handle(args);
    }

    // ── 同组放行 ───────────────────────────────────────────

    @Test
    void testSameGroupAllowed() {
        RolePool pool = new RolePool();
        Map<String, ToolKit> tks = setupRoles(pool,
                role("顾承宇", "frontend_dev_1", "前端开发组"),
                role("陈思远", "frontend_lead", "前端开发组"));
        String result = talk(tks, "frontend_dev_1", "陈思远", "组件重构完成", false);
        assertTrue(result.contains("消息已发送给 陈思远"));
        assertEquals(1, pool.getRole("frontend_lead").queueDepth());
    }

    @Test
    void testSameGroupWaitRoundtrip() throws InterruptedException {
        RolePool pool = new RolePool();
        Map<String, ToolKit> tks = setupRoles(pool,
                role("顾承宇", "frontend_dev_1", "前端开发组"),
                role("陈思远", "frontend_lead", "前端开发组"));
        AgentRole roleA = pool.getRole("frontend_dev_1");
        AtomicReference<String> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(talk(tks, "frontend_dev_1", "陈思远", "进度?", true)));
        t.start();
        try {
            assertTrue(waitUntil(() -> roleA.state == Types.AgentState.WAIT, 5000), "A 未进入 WAIT");
            String reply = talk(tks, "frontend_lead", "顾承宇", "进度 80%", false);
            assertTrue(reply.contains("已回复给正在等待的"));
            t.join(5000);
        } finally {
            t.join(1000);
            pool.shutdown(false);
        }
        assertFalse(t.isAlive());
        assertTrue(result.get().contains("已收到 陈思远 的回复: 进度 80%"));
    }

    // ── 跨组拒绝 ───────────────────────────────────────────

    @Test
    void testCrossGroupRejected() {
        RolePool pool = new RolePool();
        Map<String, ToolKit> tks = setupRoles(pool,
                role("郭晓东", "tester_1", "测试组"),
                role("王建国", "architect", "架构与版本组"));
        String result = talk(tks, "tester_1", "王建国", "有个架构问题", false);
        assertTrue(result.contains("仅限同组成员"));
        assertTrue(result.contains("测试组") && result.contains("架构与版本组"));
        assertTrue(result.contains("send_email"));
        assertEquals(0, pool.getRole("architect").queueDepth());  // 未送达
    }

    @Test
    void testCrossGroupWaitRejected() {
        RolePool pool = new RolePool();
        Map<String, ToolKit> tks = setupRoles(pool,
                role("郭晓东", "tester_1", "测试组"),
                role("方谨言", "release_manager", "架构与版本组"));
        AgentRole roleA = pool.getRole("tester_1");
        String result = talk(tks, "tester_1", "方谨言", "有急事", true);
        assertTrue(result.contains("仅限同组成员"));
        assertEquals(Types.AgentState.ON_DUTY_IDLE, roleA.state);  // 未进入 WAIT
    }

    // ── 未分组不受限 ───────────────────────────────────────

    @Test
    void testUngroupedRolesUnrestricted() {
        RolePool pool = new RolePool();
        Map<String, ToolKit> tks = setupRoles(pool,
                role("新人", "newbie_1", ""),
                role("顾承宇", "frontend_dev_1", "前端开发组"));
        String r1 = talk(tks, "newbie_1", "顾承宇", "你好", false);
        assertTrue(r1.contains("消息已发送给 顾承宇"));
        String r2 = talk(tks, "frontend_dev_1", "新人", "欢迎", false);
        assertTrue(r2.contains("消息已发送给 新人"));
    }

    // ── 花名册显示分组 ─────────────────────────────────────

    @Test
    void testRosterShowsGroup() {
        RolePool pool = new RolePool();
        pool.addRole(role("顾承宇", "frontend_dev_1", "前端开发组"));
        pool.addRole(role("林总", "CEO", "领导组"));
        pool.addRole(role("新人", "newbie_1", ""));
        String roster = ListRoles.buildTeamRoster(pool);
        assertTrue(roster.contains("(组: 前端开发组)"));
        assertTrue(roster.contains("(组: 领导组)"));
        assertTrue(roster.contains("(组: 未分组)"));
        assertFalse(roster.contains("frontend_dev_1"));
    }
}
