package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.MailService;
import com.maf.scheduler.core.ToolRegistry.ToolKit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 工具类注册表 (Python 版 python_tools/__init__.py 的 Java 对应物).
 *
 * DEFAULT_TOOLKITS (角色自动装配的工具类):
 *   memory / time / todo / task_view / computer / mcp_manager /
 *   skill_manager / email (hermes 默认关闭, 与 Python 版一致).
 *
 * DEFAULT_MCP_GROUPS: 角色加入/启动时自动把该组 MCP 工具安装到个人电脑.
 */
public final class Toolkits {

    private Toolkits() {
    }

    /** 全局共享 MCP 管理器. */
    private static final MCPManagerToolkit.MCPManager MCP_MANAGER = new MCPManagerToolkit.MCPManager();

    /** 全局共享技能库管理器. */
    private static final SkillToolkit.SkillManager SKILL_MANAGER = new SkillToolkit.SkillManager();

    /** 默认 MCP 工具组: 文件操作 MCP 工具集. */
    public static final List<String> DEFAULT_MCP_GROUPS = List.of("file_ops");

    public static MCPManagerToolkit.MCPManager getMcpManager() {
        return MCP_MANAGER;
    }

    public static SkillToolkit.SkillManager getSkillManager() {
        return SKILL_MANAGER;
    }

    /**
     * 默认工具类注册表: 角色被添加进 AgentSystem 时 (autoToolkits=true)
     * 自动逐个加载. 每个调用返回新的独立工具类实例.
     */
    public static List<ToolKit> defaultToolkits() {
        List<ToolKit> out = new ArrayList<>();
        out.add(MemoryToolkit.createMemoryToolkit());
        out.add(TimeToolkit.createTimeToolkit());
        out.add(TodoToolkit.createTodoToolkit());
        out.add(TaskViewToolkit.createTaskViewToolkit());
        out.add(ComputerToolkit.createComputerToolkit());
        out.add(MCPManagerToolkit.createMcpManagerToolkit(MCP_MANAGER));
        out.add(SkillToolkit.createSkillManagerToolkit(SKILL_MANAGER));
        out.add(EmailToolkit.createEmailToolkit(null));
        return out;
    }

    /** 工具类绑定分发表 (toolkit.name → 绑定函数). 由 AgentRole.addToolkit 调用. */
    public static Map<String, BiConsumer<ToolKit, AgentRole>> binders() {
        return ToolkitBinders.getBinders();
    }
}
