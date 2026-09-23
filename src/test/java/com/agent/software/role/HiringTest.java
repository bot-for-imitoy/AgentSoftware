package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.io.WebInput;
import com.agent.software.utils.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HR 招聘：按需求生成员工档案、进名单、可选入职、role_id 唯一。
 *
 * <p>测试把配置目录指向空目录，避免读到真实 API Key 而走 LLM 生成（那样既慢又花钱）。
 */
class HiringTest {

    /** 解析工具返回的 "post_job_posting: {…json…}"。 */
    private static Map<String, Object> info(String toolText) {
        int i = toolText.indexOf('{');
        assertTrue(i >= 0, toolText);
        return Json.parseObject(toolText.substring(i));
    }

    /** 用空配置目录构造 AgentSystem：不带 API Key，RoleFactory 走本地确定性生成。 */
    private static AgentSystem offlineSystem(Path dataDir, Path configDir) {
        System.setProperty("AGENTSOFTWARE_CONFIG_DIR", configDir.toString());
        return new AgentSystem(dataDir, new WebInput());
    }

    @Test
    void hrOwnsHiringToolsAndCanGenerateAnEmployee(@TempDir Path dir, @TempDir Path cfg) {
        AgentSystem system = offlineSystem(dir, cfg);
        try {
            Role hr = system.getRolePool().find("HR");
            assertEquals(54, system.getRoster().size());
            assertTrue(hr.getTools().stream().anyMatch(t -> t.getToolName().equals("post_job_posting")));
            assertTrue(hr.getTools().stream().anyMatch(t -> t.getToolName().equals("list_candidates")));

            var res = hr.invokeTool("post_job_posting",
                    Map.of("requirement", "需要一个熟悉 Rust 和 PostgreSQL 的后端工程师，偏工程化"));
            assertTrue(res.ok, res.text);

            Map<String, Object> profile = info(res.text);
            String roleId = String.valueOf(profile.get("role_id"));
            String name = String.valueOf(profile.get("name"));
            assertTrue(roleId.startsWith("rust"), roleId);
            assertFalse(name.isBlank());
            assertEquals("OUT_OF_GROUP", profile.get("membership"));
            assertFalse(((List<?>) profile.get("skills")).isEmpty());
            assertFalse(((List<?>) profile.get("interest_keywords")).isEmpty());

            // 进了公司名单，但没进大组
            assertEquals(55, system.getRoster().size());
            Employee hired = system.getRoster().find(roleId);
            assertNotNull(hired);
            assertEquals(MembershipState.OUT_OF_GROUP, hired.membership);
            assertNull(system.getRolePool().find(roleId), "默认不自动进组（由 COO draft_in 决定）");

            // list_candidates 能看到他
            var list = hr.invokeTool("list_candidates", Map.of("query", roleId));
            assertTrue(list.text.contains(roleId), list.text);

            // 同一需求再招一次：role_id 必须唯一
            var res2 = hr.invokeTool("post_job_posting",
                    Map.of("requirement", "需要一个熟悉 Rust 和 PostgreSQL 的后端工程师，偏工程化"));
            assertTrue(res2.ok, res2.text);
            String roleId2 = String.valueOf(info(res2.text).get("role_id"));
            assertFalse(roleId2.equals(roleId), "role_id 不能重复");
            assertEquals(56, system.getRoster().size());
        } finally {
            system.stop();
            System.clearProperty("AGENTSOFTWARE_CONFIG_DIR");
        }
    }

    @Test
    void draftInHiresIntoTheCohortImmediately(@TempDir Path dir, @TempDir Path cfg) {
        AgentSystem system = offlineSystem(dir, cfg);
        try {
            Role hr = system.getRolePool().find("HR");
            var res = hr.invokeTool("post_job_posting", Map.of(
                    "requirement", "需要一个 mobile engineer",
                    "group", "Mobile Development Group",
                    "draft_in", true));
            assertTrue(res.ok, res.text);

            Map<String, Object> profile = info(res.text);
            String roleId = String.valueOf(profile.get("role_id"));
            assertEquals("IN_GROUP", profile.get("membership"));
            assertEquals("Mobile Development Group", profile.get("group"));

            Role hired = system.getRolePool().find(roleId);
            assertNotNull(hired, "draft_in=true 应立刻进组");
            assertEquals(6, system.getRolePool().size());
            assertTrue(hired.hasComputer());
        } finally {
            system.stop();
            System.clearProperty("AGENTSOFTWARE_CONFIG_DIR");
        }
    }

    @Test
    void postingWithoutRequirementFails(@TempDir Path dir, @TempDir Path cfg) {
        AgentSystem system = offlineSystem(dir, cfg);
        try {
            Role hr = system.getRolePool().find("HR");
            var res = hr.invokeTool("post_job_posting", Map.of());
            assertFalse(res.ok, res.text);
            assertTrue(res.text.contains("needs requirement"), res.text);
            assertEquals(54, system.getRoster().size());
        } finally {
            system.stop();
            System.clearProperty("AGENTSOFTWARE_CONFIG_DIR");
        }
    }
}
