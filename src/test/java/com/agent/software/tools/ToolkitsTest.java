package com.agent.software.tools;

import com.agent.software.role.Employee;
import com.agent.software.role.Role;
import com.agent.software.tools.toolkits.mcp.MCPManager;
import com.agent.software.tools.toolkits.skill.SkillManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 默认工具集工厂：基础工具包 + 管理组的 client + COO 的调度。 */
class ToolkitsTest {

    private static List<String> names(Role role) {
        return Toolkits.defaults(role, null, null, new MCPManager(), new SkillManager())
                .stream().map(Toolkit::getName).sorted().toList();
    }

    private static Role role(String roleId, String group) {
        return new Role(new Employee(roleId, roleId + " Name", group));
    }

    @Test
    void defaultSetContainsCoreToolkits() {
        List<String> names = names(role("architect", "Architecture & Release Group"));
        assertTrue(names.contains("time"), names.toString());
        assertTrue(names.contains("task"));
        assertTrue(names.contains("note"));
        assertTrue(names.contains("todo"));
        assertTrue(names.contains("memory"));
        assertTrue(names.contains("pc"));
        assertTrue(names.contains("mcp_manager"));
        assertTrue(names.contains("skill"));
        assertTrue(names.contains("email"));
        assertFalse(names.contains("talk"), "talk 暂时不进默认工具集（待修）");
        assertFalse(names.contains("client"), "非管理组没有 talk_to_client");
        assertFalse(names.contains("staffing_toolkit"));
    }

    /** talk 不在默认集里，但配置显式写 "talk" 时仍应生效（能力没删，只是默认关掉）。 */
    @Test
    void talkCanStillBeEnabledExplicitly(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path cfg = dir.resolve("toolkits.default.json");
        com.agent.software.utils.Json.writeFile(cfg,
                java.util.Map.of("default_toolkits", java.util.List.of("talk")));
        Role r = role("architect", "Architecture & Release Group");
        List<String> names = Toolkits.defaults(r,
                        new com.agent.software.store.ToolkitConfig(
                                com.agent.software.store.JsonStore.of(cfg)),
                        null, new MCPManager(), new SkillManager())
                .stream().map(Toolkit::getName).sorted().toList();
        assertTrue(names.contains("talk"), names.toString());
    }

    @Test
    void managementGetsClientToolkit() {
        List<String> names = names(role("CEO", "Leadership Group"));
        assertTrue(names.contains("client"));
    }

    @Test
    void cooGetsStaffingToolkit() {
        List<String> names = names(role("COO", "Leadership Group"));
        assertTrue(names.contains("staffing_toolkit"), names.toString());
    }

    @Test
    void hrGetsHiringToolkit() {
        List<String> names = names(role("HR", "Leadership Group"));
        assertTrue(names.contains("hr"), names.toString());
    }

    @Test
    void toolkitExposesItsToolsAndTrigger() {
        Role r = role("CEO", "Leadership Group");
        Toolkit time = Toolkits.defaults(r, null, null, new MCPManager(), new SkillManager())
                .stream().filter(t -> t.getName().equals("time")).findFirst().orElseThrow();
        List<String> toolNames = time.getTools().stream().map(Tool::getToolName).toList();
        assertTrue(toolNames.contains("get_time"));
        assertTrue(toolNames.contains("take_rest"));
        assertTrue(time.size() >= 2);
        assertFalse(time.trigger("no_such_tool", java.util.Map.of()) != null);

        // 角色实际拿到的 task 工具包里必须有 complete_task（不然只有手搭的 toolkit 才有）
        Toolkit task = Toolkits.defaults(r, null, null, new MCPManager(), new SkillManager())
                .stream().filter(t -> t.getName().equals("task")).findFirst().orElseThrow();
        assertTrue(task.getTools().stream().map(Tool::getToolName).toList().contains("complete_task"),
                "task 工具包缺 complete_task");
    }
}
