package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.ToolRegistry.ToolKit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 工具类注册表 (Python 版 python_tools/__init__.py 的 Java 对应物).
 *
 * DEFAULT_TOOLKITS (角色自动装配的工具类, 全部为模板风格 toolkits.* 实现):
 *   memory / note / time / todo / task_view / pc / mcp_manager /
 *   skill_manager / email (hermes 默认关闭, 与 Python 版一致).
 *
 * 注意:
 *   - note 工具已从 memory 中分离: memory 只保留记忆相关内容 (summary),
 *     笔记操作 (write_note/edit_note/list_notes/read_note/delete_note) 在
 *     toolkits.note.Note 中.
 *   - pc 即 computer 工具 (toolkits.pc.Pc): run_command / computer_status /
 *     lan_devices / reboot.
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
     * 自动逐个加载 (RolePool.setupRole 调用, 传入具体角色以便工具类绑定).
     * 每个调用返回新的独立模板风格工具类实例 (AgentRole.addToolkit(Toolkit)
     * 会自动桥接为旧版 ToolKit 供 LLM 调用).
     */
    public static List<com.maf.scheduler.tools.Toolkit> defaultToolkits(AgentRole role) {
        List<com.maf.scheduler.tools.Toolkit> out = new ArrayList<>();
        out.add(new com.maf.scheduler.tools.toolkits.memory.Memory(role));
        out.add(new com.maf.scheduler.tools.toolkits.note.Note(role));
        out.add(new com.maf.scheduler.tools.toolkits.time.Time(role));
        out.add(new com.maf.scheduler.tools.toolkits.todo.Todo(role));
        out.add(new com.maf.scheduler.tools.toolkits.taskview.TaskView(role));
        out.add(new com.maf.scheduler.tools.toolkits.pc.Pc(role));
        out.add(new com.maf.scheduler.tools.toolkits.mcp.McpManager(role, MCP_MANAGER));
        out.add(new com.maf.scheduler.tools.toolkits.skill.Skill(role, SKILL_MANAGER));
        out.add(new com.maf.scheduler.tools.toolkits.email.Email(role, null));
        return out;
    }

    /** 工具类绑定分发表 (toolkit.name → 绑定函数). 由 AgentRole.addToolkit 调用. */
    public static Map<String, BiConsumer<ToolKit, AgentRole>> binders() {
        return ToolkitBinders.getBinders();
    }
}
