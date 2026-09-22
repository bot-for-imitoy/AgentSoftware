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
}
