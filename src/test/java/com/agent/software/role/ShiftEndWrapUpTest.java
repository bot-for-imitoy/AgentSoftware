package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.io.WebInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下班收工任务：18:00 之后**每个**大组成员（哪怕当时正闲着）都要被叫起来收尾 ——
 * 整理、排明天、写每日总结、休息 —— 然后才把这一天从 prompt 里翻篇。
 *
 * <p>补的是"只在工具结果里附提醒"留下的洞：空闲角色当时没有工具结果可附，于是完全没有收尾。
 */
class ShiftEndWrapUpTest {

    @Test
    void everyCohortMemberGetsAWrapUpTaskAtShiftEnd(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            List<Role> cohort = system.getRolePool().all();
            assertEquals(5, cohort.size(), "默认大组 = 管理组");
            for (Role r : cohort) {
                r.setLlm(new FakeLlm());
            }

            system.getTimeBus().advanceTo(1);          // 第 1 天 08:00 开工
            awaitShiftStart(cohort);
            for (Role r : cohort) {
                assertTrue(r.getContext().messages().size() > 0, r.roleId + " 开工后应有上下文");
                assertEquals(1, r.getContext().getDay(), r.roleId + " 还没下班，仍是第 1 天");
            }

            system.getTimeBus().advanceTo(36_001);     // 18:00 下班（全员空闲）
            awaitDayClosed(cohort);
            for (Role r : cohort) {
                String h = history(r);
                assertTrue(h.contains("[time] Shift end at "), r.roleId + " 应收到收工任务: " + h);
                assertTrue(h.contains("workday is over"), r.roleId + " 收工任务要带收尾流程: " + h);
                assertTrue(h.contains("daily summary"), r.roleId + " 要提醒写每日总结: " + h);
                assertTrue(h.contains("take_rest"), r.roleId + " 要提醒最后休息: " + h);
                assertEquals(2, r.getContext().getDay(), r.roleId + " 收工后应翻篇到第 2 天");
                assertTrue(r.getContext().messages().isEmpty(),
                        r.roleId + " 收工后 prompt 里不该还留着今天的消息: " + history(r));
            }
        } finally {
            system.stop();
        }
    }

    /** 收工任务跑完（= 日期翻篇）才算结束。 */
    private static void awaitDayClosed(List<Role> cohort) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && cohort.stream().anyMatch(r -> r.getContext().getDay() < 2)) {
            sleep();
        }
    }

    private static void awaitShiftStart(List<Role> cohort) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && cohort.stream().anyMatch(r -> !history(r).contains("[time] Shift start at"))) {
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String history(Role role) {
        StringBuilder sb = new StringBuilder();
        for (var m : role.getContext().history()) {
            sb.append(m.getRole()).append(':').append(m.content).append('\n');
        }
        return sb.toString();
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
