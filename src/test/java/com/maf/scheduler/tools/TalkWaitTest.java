package com.maf.scheduler.tools;

import com.maf.scheduler.role.AgentRole;
import com.maf.scheduler.role.RolePool;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.tools.toolkits.talk.ListRoles;
import com.maf.scheduler.tools.toolkits.talk.Talk;
import com.maf.scheduler.core.ToolRegistry.ToolHandler;
import com.maf.scheduler.core.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * talk 工具 wait=true 同步等待回复测试 (Python 版 test_talk_wait.py 的 Java 对应物).
 */
class TalkWaitTest {

    @TempDir
    Path tmp;

    private static boolean waitUntil(java.util.function.Supplier<Boolean> pred, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(pred.get())) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    private static Map<String, ToolKit> setupRoles(RolePool pool, String... roleIds) {
        Map<String, ToolKit> toolkits = new LinkedHashMap<>();
        for (String rid : roleIds) {
            AgentRole role = AgentRole.builder().name("角色" + rid).roleId(rid).build();
            pool.addRole(role);
            role.setPool(pool);  // 模拟 start() 后的 back-reference
            ToolKit tk = ToolkitBridge.toLegacy(new Talk(role, pool));
            toolkits.put(rid, tk);
        }
        return toolkits;
    }

    /** 调用发送方 talk 处理器 (与 call_tool 同一逻辑). */
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

    // ── 人名暴露 ──────────────────────────────────────────

    @Test
    void testRosterHidesRoleId() {
        RolePool pool = new RolePool();
        pool.addRole(AgentRole.builder().name("张三").roleId("dev_1").build());
        pool.addRole(AgentRole.builder().name("李四").roleId("dev_2").build());
        String roster = ListRoles.buildTeamRoster(pool);
        assertTrue(roster.contains("张三") && roster.contains("李四"));
        assertFalse(roster.contains("dev_1"));
        assertFalse(roster.contains("dev_2"));
        assertFalse(roster.contains("role_id"));
    }

    @Test
    void testTalkByPersonName() {
        RolePool pool = new RolePool();
        Map<String, ToolKit> tks = setupRoles(pool, "A", "B");
        AgentRole roleB = pool.getRole("B");
        String result = talk(tks, "A", "角色B", "按名字发送测试", false);
        assertTrue(result.contains("消息已发送给 角色B"));
        assertEquals(1, roleB.queueDepth());
    }

    @Test
    void testTalkUnknownNameGivesHint() {
        RolePool pool = new RolePool();
        Map<String, ToolKit> tks = setupRoles(pool, "A", "B");
        String result = talk(tks, "A", "不存在的名字", "hi", false);
        assertTrue(result.contains("找不到"));
        assertTrue(result.contains("list_roles"));
    }

    // ── 1) wait 往返 ──────────────────────────────────────

    @Test
    void testWaitRoundtrip() throws InterruptedException {
        RolePool pool = new RolePool();
        Map<String, ToolKit> tks = setupRoles(pool, "A", "B");
        AgentRole roleA = pool.getRole("A");
        AtomicReference<String> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(talk(tks, "A", "B", "请问进度?", true)));
        t.start();
        try {
            assertTrue(waitUntil(() -> roleA.state == Types.AgentState.WAIT, 5000), "A 未进入 WAIT");
            String reply = talk(tks, "B", "A", "进度 80%", false);
            assertTrue(reply.contains("已回复给正在等待的"));
            t.join(5000);
        } finally {
            t.join(1000);
            pool.shutdown(false);
        }
        assertFalse(t.isAlive());
        assertTrue(result.get().contains("已收到 角色B 的回复: 进度 80%"));
        assertEquals(Types.AgentState.ON_DUTY_IDLE, roleA.state);  // 状态恢复
    }

    @Test
    void testWaitRoundtripWithRealNames() throws InterruptedException {
        RolePool pool = new RolePool();
        AgentRole a = AgentRole.builder().name("王建国").roleId("architect").build();
        AgentRole b = AgentRole.builder().name("郭晓东").roleId("tester_1").build();
        pool.addRole(a);
        pool.addRole(b);
        a.setPool(pool);
        b.setPool(pool);
        Map<String, ToolKit> tks = new LinkedHashMap<>();
        for (AgentRole r : new AgentRole[]{a, b}) {
            ToolKit tk = ToolkitBridge.toLegacy(new Talk(r, pool));
            tks.put(r.roleId, tk);
        }
        AtomicReference<String> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(talk(tks, "architect", "郭晓东", "进度?", true)));
        t.start();
        try {
            assertTrue(waitUntil(() -> a.state == Types.AgentState.WAIT, 5000), "A 未进入 WAIT");
            assertEquals("tester_1", a.waitingReplyFrom());  // 内部等待链存 role_id
            String reply = talk(tks, "tester_1", "王建国", "进度 80%", false);
            assertTrue(reply.contains("已回复给正在等待的"));
            t.join(5000);
        } finally {
            t.join(1000);
            pool.shutdown(false);
        }
        assertFalse(t.isAlive());
        assertTrue(result.get().contains("已收到 郭晓东 的回复: 进度 80%"));
        assertEquals(Types.AgentState.ON_DUTY_IDLE, a.state);
    }

    // ── 2) 双向互等拆解 ───────────────────────────────────

    @Test
    void testMutualWaitDecomposed() {
        RolePool pool = new RolePool();
        Map<String, ToolKit> tks = setupRoles(pool, "A", "B");
        AgentRole roleA = pool.getRole("A");
        AgentRole roleB = pool.getRole("B");
        roleA.beginWait("B");  // A 正在等 B 的回复
        try {
            String result = talk(tks, "B", "A", "收到, 马上处理", true);
            assertTrue(result.contains("已回复给正在等待的"));
            assertEquals("收到, 马上处理", roleA.debugReplyBox());  // 投递进 A 的信箱
            assertEquals(Types.AgentState.ON_DUTY_IDLE, roleB.state);  // B 没有进入 WAIT
        } finally {
            roleA.endWait();
        }
    }

    // ── 3) 环形等待拒绝 ───────────────────────────────────

    @Test
    void testDeadlockCycleRejected() {
        RolePool pool = new RolePool();
        Map<String, ToolKit> tks = setupRoles(pool, "A", "B", "C");
        AgentRole roleA = pool.getRole("A");
        AgentRole roleB = pool.getRole("B");
        AgentRole roleC = pool.getRole("C");
        roleB.beginWait("C");
        roleC.beginWait("A");
        try {
            String result = talk(tks, "A", "B", "有急事", true);
            assertTrue(result.contains("死锁"));
            assertEquals(Types.AgentState.ON_DUTY_IDLE, roleA.state);  // A 未进入 WAIT
        } finally {
            roleB.endWait();
            roleC.endWait();
        }
    }

    // ── 4) 无限等待 + 等待提示 ────────────────────────────

    @Test
    void testWaitMessageCarriesWaitingHint() throws InterruptedException {
        RolePool pool = new RolePool();
        Map<String, ToolKit> tks = setupRoles(pool, "A", "B");
        AgentRole roleA = pool.getRole("A");
        AgentRole roleB = pool.getRole("B");
        AtomicReference<String> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(talk(tks, "A", "B", "进度如何?", true)));
        t.start();
        try {
            assertTrue(waitUntil(() -> roleB.queueDepth() == 1, 5000), "B 未收到消息");
            AgentRole.Task task = roleB.popTask();
            assertTrue(task != null && task.description.contains("正在等待你的回复"));
            assertTrue(task.description.contains("角色A"));
            assertEquals(Boolean.TRUE, task.context.get("waiting"));
            // 无超时: 等 1.5s 仍在 WAIT
            Thread.sleep(1500);
            assertEquals(Types.AgentState.WAIT, roleA.state);
            String reply = talk(tks, "B", "A", "进度 80%", false);
            assertTrue(reply.contains("已回复给正在等待的"));
            t.join(5000);
        } finally {
            t.join(1000);
            pool.shutdown(false);
        }
        assertFalse(t.isAlive());
        assertTrue(result.get().contains("已收到 角色B 的回复: 进度 80%"));
        assertEquals(Types.AgentState.ON_DUTY_IDLE, roleA.state);
    }

    // ── 5) 非等待对象消息不投递 ───────────────────────────

    @Test
    void testThirdPartyMessageNotDeliveredAsReply() {
        RolePool pool = new RolePool();
        Map<String, ToolKit> tks = setupRoles(pool, "A", "B", "C");
        AgentRole roleA = pool.getRole("A");
        roleA.beginWait("B");
        try {
            String result = talk(tks, "C", "A", "普通消息", false);
            assertTrue(result.contains("消息已发送给"));
            assertEquals(Types.AgentState.WAIT, roleA.state);  // 仍在等待 B
            assertNull(roleA.debugReplyBox());
            assertEquals(1, roleA.queueDepth());  // 消息入队, 恢复后处理
        } finally {
            roleA.endWait();
        }
    }

    // ── 6) 附件 (公司云盘 /mnt/drive 文件) ────────────────

    @Test
    void testTalkAttachmentValidatedAndCarried() {
        AgentRole.JOURNAL_DIR = tmp.resolve("journals");
        RolePool pool = new RolePool();
        Map<String, Object> kw = new LinkedHashMap<>();
        kw.put("drive_dir", tmp.resolve("drive").toString());
        AgentRole a = AgentRole.builder().name("郭晓东").roleId("tester_1")
                .computerKind("local").computerKwargs(kw).build();
        AgentRole b = AgentRole.builder().name("王建国").roleId("architect")
                .computerKind("local").computerKwargs(kw).build();
        pool.addRole(a);
        pool.addRole(b);
        // 郭晓东在云盘放附件
        a.computer().writeFile(a.computer().driveRoot() + "/郭晓东/设计稿.md", "附件内容");

        ToolKit tkA = ToolkitBridge.toLegacy(new Talk(a, pool));
        ToolKit tkB = ToolkitBridge.toLegacy(new Talk(b, pool));
        ToolHandler talkA = tkA.getTool("talk").handler;

        // 无效附件 (不存在) → 拒绝
        Map<String, Object> badArgs = new LinkedHashMap<>();
        badArgs.put("target", "王建国");
        badArgs.put("message", "看下");
        badArgs.put("attachment", "郭晓东/不存在.md");
        String r = talkA.handle(badArgs);
        assertTrue(r.contains("附件无效"));
        assertEquals(0, b.queueDepth());

        // 有效附件 → 送达, 任务描述带附件提示
        Map<String, Object> okArgs = new LinkedHashMap<>();
        okArgs.put("target", "王建国");
        okArgs.put("message", "看下设计稿");
        okArgs.put("attachment", "郭晓东/设计稿.md");
        String r2 = talkA.handle(okArgs);
        assertTrue(r2.contains("消息已发送给 王建国"));
        AgentRole.Task task = b.popTask();
        assertTrue(task != null && task.description.contains("[附件: 郭晓东/设计稿.md]"));
        assertTrue(task.description.contains("mnt/drive"));
        assertEquals("郭晓东/设计稿.md", task.context.get("attachment"));
        pool.shutdown(false);
    }
}
