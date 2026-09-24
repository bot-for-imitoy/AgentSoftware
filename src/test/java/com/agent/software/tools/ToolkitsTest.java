package com.agent.software.tools;

import com.agent.software.role.Employee;
import com.agent.software.role.Role;
import com.agent.software.tools.toolkits.mcp.MCPManager;
import com.agent.software.tools.toolkits.skill.SkillManager;
import org.junit.jupiter.api.Test;

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
        assertTrue(names.contains("talk"));
        assertTrue(names.contains("memory"));
        assertTrue(names.contains("pc"));
        assertTrue(names.contains("mcp_manager"));
        assertTrue(names.contains("skill"));
        assertTrue(names.contains("email"));
        assertTrue(names.contains("talk"), "同组沟通用的 talk 应该人人都有");
        assertFalse(names.contains("client"), "非管理组没有 talk_to_client");
        assertFalse(names.contains("staffing_toolkit"));
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
    }
}
