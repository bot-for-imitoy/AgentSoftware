package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.io.WebInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 名单 / 大组 / 抽调：默认只含管理组，COO 可增删。 */
class StaffingTest {

    @Test
    void defaultCohortIsManagementGroupWithoutCfo(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            CompanyRoster roster = system.getRoster();
            assertEquals(54, roster.size());
            assertNull(roster.find("CFO"));

            List<String> cohort = system.getRolePool().all().stream().map(r -> r.roleId).sorted().toList();
            assertEquals(List.of("CEO", "COO", "CTO", "HR", "business_analyst"), cohort);
            for (String id : cohort) {
                assertEquals(MembershipState.IN_GROUP, roster.find(id).membership);
            }
            // 其它员工是"假死"：只有名单，没有 Role 实例
            assertNull(system.getRolePool().find("architect"));
            assertEquals(MembershipState.OUT_OF_GROUP, roster.find("architect").membership);
        } finally {
            system.stop();
        }
    }

    @Test
    void draftInThenOutKeepsRosterEntryAndData(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role drafted = system.getStaffing().draftIn("architect");
            assertNotNull(drafted);
            assertEquals(6, system.getRolePool().size());
            assertEquals(MembershipState.IN_GROUP, system.getRoster().find("architect").membership);
            assertTrue(drafted.hasComputer());
            assertTrue(drafted.isRunning());

            system.getStaffing().draftOut("architect");
            assertEquals(5, system.getRolePool().size());
            assertNull(system.getRolePool().find("architect"));
            assertNotNull(system.getRoster().find("architect"));
            assertEquals(MembershipState.OUT_OF_GROUP, system.getRoster().find("architect").membership);
            assertFalse(drafted.isRunning());
        } finally {
            system.stop();
        }
    }

    @Test
    void draftingAnUnknownEmployeeFails(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> system.getStaffing().draftIn("does_not_exist"));
        } finally {
            system.stop();
        }
    }

    /**
     * uid 必须"按员工"稳定（requirements-2 §A.3）：COO 会反复抽调/移出，
     * 如果 uid 跟着入组顺序走，容器内的文件归属会跟着漂。
     */
    @Test
    void uidIsStablePerEmployeeAcrossDraftOutAndIn(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            int ceoUid = system.getRolePool().find("CEO").uid;
            assertTrue(ceoUid >= 1101, "uid 从 1101 起: " + ceoUid);

            Role first = system.getStaffing().draftIn("architect");
            int architectUid = first.uid;
            system.getStaffing().draftOut("architect");
            Role again = system.getStaffing().draftIn("architect");
            assertEquals(architectUid, again.uid, "重新进组必须还是同一个 uid");

            // 中间进出的别人，不影响已有成员的 uid
            system.getStaffing().draftIn("frontend_dev_1");
            system.getStaffing().draftOut("frontend_dev_1");
            assertEquals(ceoUid, system.getRolePool().find("CEO").uid);
            assertEquals(architectUid, system.getRolePool().find("architect").uid);

            // 同一批人的 uid 互不相同
            var uids = system.getRolePool().all().stream().map(r -> r.uid).toList();
            assertEquals(uids.size(), uids.stream().distinct().count(), "uid 不能重复: " + uids);
        } finally {
            system.stop();
        }
    }
}
