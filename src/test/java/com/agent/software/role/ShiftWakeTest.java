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
 * 每天开工时唤醒大组成员。
 *
 * <p>原实现在上班时把广播的 SHIFT_START 变成每个角色的一条任务，角色因此真的跑一轮；
 * refactor3 首版只改状态就 return，全员静默。这里验证：班次开始 → 大组内每个角色都收到
 * "开工"任务，且每天重来一次。
 */
class ShiftWakeTest {

    @Test
    void everyCohortMemberIsWokenAtShiftStartEachDay(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            List<Role> cohort = system.getRolePool().all();
            assertEquals(5, cohort.size(), "默认大组 = 管理组");
            for (Role r : cohort) {
                r.setLlm(new FakeLlm());   // 任务瞬间结束，便于确定性统计"被唤醒几次"
            }

            // 第 1 天：进入班次 → SHIFT_START
            system.getTimeBus().advanceTo(1);
            awaitWakes(cohort, 1);
            for (Role r : cohort) {
                assertEquals(1, wakes(r), r.roleId + " 应在 08:00 被唤醒: " + r.readJournal());
                assertTrue(history(r).contains("[time] Shift start at "), r.roleId + ": " + history(r));
                assertTrue(history(r).contains("(day 1)"), r.roleId + ": " + history(r));
            }

            // 下班 → 次日 08:00：必须再来一次
            system.getTimeBus().advanceTo(36_001);
            system.getTimeBus().advanceTo(86_401);
            awaitWakes(cohort, 2);
            for (Role r : cohort) {
                assertEquals(2, wakes(r), r.roleId + " 应在第二天 08:00 再次被唤醒: " + r.readJournal());
                assertTrue(history(r).contains("(day 2)"), r.roleId + ": " + history(r));
            }
        } finally {
            system.stop();
        }
    }

    /** 第 1 天也必须是 08:00 开工：不能因为空闲快进被推迟到第一个排期事件。 */
    @Test
    void theFirstDayIsWokenAt0800(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            List<Role> cohort = system.getRolePool().all();
            for (Role r : cohort) {
                r.setLlm(new FakeLlm());
            }
            system.start();
            awaitWakes(cohort, 1);
            for (Role r : cohort) {
                String h = history(r);
                assertTrue(h.contains("Shift start at ") && h.contains("08:00") && h.contains("(day 1)"),
                        r.roleId + " 首日应在 08:00 被唤醒: " + h);
            }
        } finally {
            system.stop();
        }
    }

    /** 中途入职（HR 招进来）的人，从下一个班次开始一起被唤醒。 */
    @Test
    void aNewlyDraftedMemberIsWokenFromTheNextShiftStart(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            for (Role r : system.getRolePool().all()) {
                r.setLlm(new FakeLlm());
            }
            system.getTimeBus().advanceTo(1);                       // 第 1 天开工
            awaitWakes(system.getRolePool().all(), 1);

            Role architect = system.getStaffing().draftIn("architect");   // 当天中途入职
            architect.setLlm(new FakeLlm());
            assertEquals(0, wakes(architect), "入职当天不补发开工任务");

            system.getTimeBus().advanceTo(36_001);                  // 下班
            system.getTimeBus().advanceTo(86_401);                  // 次日 08:00
            awaitWakes(List.of(architect), 1);
            assertEquals(1, wakes(architect), "次日应被一起唤醒: " + architect.readJournal());
            assertTrue(history(architect).contains("[time] Shift start at "), history(architect));
        } finally {
            system.stop();
        }
    }

    /** 被唤醒（跑完）过几次：每个任务收尾都会写一条 answer 日志。 */
    private static long wakes(Role role) {
        return role.readJournal().stream().filter(l -> l.contains("answer(")).count();
    }

    private static String history(Role role) {
        StringBuilder sb = new StringBuilder();
        for (var m : role.getContext().history()) {
            sb.append(m.getRole()).append(':').append(m.content).append('\n');
        }
        return sb.toString();
    }

    private static void awaitWakes(List<Role> cohort, long expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && cohort.stream().anyMatch(r -> wakes(r) < expected)) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
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
            return new com.agent.software.llm.Response("ok", "", List.of(), 0);
        }
    }
}
