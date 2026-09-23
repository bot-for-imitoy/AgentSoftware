package com.agent.software.computers;

import com.agent.software.AgentSystem;
import com.agent.software.io.WebInput;
import com.agent.software.role.Employee;
import com.agent.software.role.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 电脑的目录布局与容器内初始化脚本。
 *
 * <p>修的是两个 bug：云盘原来按 {@code data/drive/<roleId>} 每角色挂一份（应该是全体共用的
 * {@code data/drive}），容器里也从来没建过员工自己的用户。沙箱里跑不了 podman，所以这里断言
 * 目录语义和初始化脚本的正文。
 */
class ComputerLayoutTest {

    private static Role role(String roleId, String name, String username) {
        Employee e = new Employee(roleId, name, "Leadership Group");
        e.template.put("username", username);
        return new Role(e);
    }

    private static Path dataRoot(String... more) {
        Path root = Path.of(System.getenv().getOrDefault("AGENTSOFTWARE_DATA_DIR", "data"))
                .toAbsolutePath();
        for (String m : more) {
            root = root.resolve(m);
        }
        return root;
    }

    @Test
    void everyComputerMountsTheSameSharedDriveButItsOwnHome() {
        LocalComputer ceo = new LocalComputer(role("CEO", "Lin Zong", "linzong"));
        LocalComputer coo = new LocalComputer(role("COO", "Chen Zong", "chenzong"));

        // 云盘是共享的：同一份宿主机目录，映射到 /mnt/drive
        assertEquals(dataRoot("drive"), ceo.driveRoot());
        assertEquals(ceo.driveRoot(), coo.driveRoot(), "所有电脑必须挂同一个云盘目录");
        assertEquals("/mnt/drive", Computer.DRIVE_MOUNT);

        // 主目录 / 电脑目录 / 云盘个人目录都是各自的
        assertEquals("/home/linzong", ceo.workdir());
        assertEquals("/home/chenzong", coo.workdir());
        assertEquals(dataRoot("computers", "CEO"), ceo.hostDir());
        assertEquals(dataRoot("computers", "COO"), coo.hostDir());
        assertEquals("linzong", ceo.driveDirName());
        assertEquals("chenzong", coo.driveDirName());
        assertNotEquals(ceo.hostDir(), coo.hostDir());
    }

    @Test
    void usernameFallsBackToRoleIdAndUidToBase() {
        Role r = new Role(new Employee("tester_9", "Some One", "Testing Group"));
        LocalComputer c = new LocalComputer(r);
        assertEquals("tester_9", c.username(), "模板没给 username 时退化成 role_id");
        assertEquals("/home/tester_9", c.workdir());
        assertEquals(1100, c.uid(), "uid 未分配时用基准值");
    }

    @Test
    void containerUserIsCreatedWithPasswordlessSudo() {
        String script = PodmanComputer.userSetupScript("linzong", 1101, "/home/linzong");
        assertTrue(script.contains("useradd -s /bin/bash -u 1101 -G sudo 'linzong'"), script);
        assertTrue(script.contains("id -u 'linzong' >/dev/null 2>&1 ||"), "必须幂等: " + script);
        assertTrue(script.contains("/etc/sudoers.d/"), script);
        assertTrue(script.contains("ALL=(ALL) NOPASSWD:ALL"), script);
        assertTrue(script.contains("mkdir -p '/home/linzong'"), script);
        assertTrue(script.contains("chown -R 1101:1101 '/home/linzong'"), script);
    }

    @Test
    void cloudDriveDirsAreCreatedInTheContainer() {
        String script = PodmanComputer.driveSetupScript("/mnt/drive/linzong", 1101, false);
        assertTrue(script.contains("mkdir -p /mnt/drive/Public '/mnt/drive/linzong'"), script);
        assertTrue(script.contains("chmod 777 /mnt/drive/Public"), script);
        assertTrue(script.contains("chmod 755 '/mnt/drive/linzong'"), script);
        assertTrue(script.contains("chown 1101:1101 '/mnt/drive/linzong'"), script);
        // 非 CEO 不接管 Public
        assertFalse(script.contains("chown 1101:1101 /mnt/drive/Public"), script);

        String ceoScript = PodmanComputer.driveSetupScript("/mnt/drive/linzong", 1101, true);
        assertTrue(ceoScript.contains("chown 1101:1101 /mnt/drive/Public"), ceoScript);
    }

    @Test
    void cohortMembersGetDistinctContainerUidsAndTheSharedDrive(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            List<Role> cohort = system.getRolePool().all();
            List<Integer> uids = cohort.stream().map(r -> r.uid).toList();
            assertEquals(cohort.size(), uids.stream().distinct().count(), "uid 必须互不相同: " + uids);
            assertTrue(uids.stream().allMatch(u -> u >= 1101), "uid 从 1101 起分配: " + uids);

            Path drive = system.getComputerManager().findByRoleId("CEO").driveRoot();
            for (Computer c : system.getComputerManager().all()) {
                assertEquals(drive, c.driveRoot(), c.getRole().roleId + " 应挂同一个云盘目录");
                assertNotEquals(c.driveRoot(), c.hostDir());
            }
        } finally {
            system.stop();
        }
    }
}
