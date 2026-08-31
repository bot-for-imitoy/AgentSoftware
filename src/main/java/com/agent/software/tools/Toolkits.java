package com.agent.software.tools;

import com.agent.software.AgentSystemContext;
import com.agent.software.tools.toolkits.client.Client;
import com.agent.software.tools.toolkits.email.Email;
import com.agent.software.tools.toolkits.mcp.McpManager;
import com.agent.software.tools.toolkits.memory.Memory;
import com.agent.software.tools.toolkits.note.Note;
import com.agent.software.tools.toolkits.pc.Pc;
import com.agent.software.tools.toolkits.skill.Skill;
import com.agent.software.tools.toolkits.taskview.TaskView;
import com.agent.software.tools.toolkits.time.Time;
import com.agent.software.tools.toolkits.todo.Todo;
import com.agent.software.role.AgentRole;
import com.agent.software.tools.toolkits.mcp.MCPManager;
import com.agent.software.tools.toolkits.skill.SkillManager;

import java.util.ArrayList;
import java.util.List;

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
    private static final MCPManager MCP_MANAGER = new MCPManager();

    /** 全局共享技能库管理器. */
    private static final SkillManager SKILL_MANAGER = new SkillManager();

    /** 默认 MCP 工具组: 文件操作 MCP 工具集. */
    public static final List<String> DEFAULT_MCP_GROUPS = List.of("file_ops");

    /** 领导组组名 (对应 role_templates.json 的 group 字段). */
    public static final String LEADERSHIP_GROUP = "Leadership Group";

    public static MCPManager getMcpManager() {
        return MCP_MANAGER;
    }

    public static SkillManager getSkillManager() {
        return SKILL_MANAGER;
    }

    /**
     * 默认工具类注册表: 角色被添加进 AgentSystem 时 (autoToolkits=true)
     * 自动逐个加载 (RolePool.setupRole 调用, 传入具体角色以便工具类绑定).
     * 每个调用返回新的独立模板风格工具类实例 (AgentRole.addToolkit(Toolkit)
     * 会自动桥接为旧版 ToolKit 供 LLM 调用).
     *
     * <p>工具类使用的协作对象 (MCP/技能/邮箱/对话锁) 优先取角色所属
     * {@link AgentSystemContext} 的每系统实例, 未绑定上下文的独立角色
     * 回退到本类的进程级默认单例 (旧行为).
     */
    public static List<Toolkit> defaultToolkits(AgentRole role) {
        AgentSystemContext ctx = role != null ? role.context() : null;
        MCPManager mcpManager = ctx != null ? ctx.mcpManager : MCP_MANAGER;
        SkillManager skillManager = ctx != null ? ctx.skillManager : SKILL_MANAGER;
        List<Toolkit> out = new ArrayList<>();
        out.add(new Memory(role));
        out.add(new Note(role));
        out.add(new Time(role));
        out.add(new Todo(role));
        out.add(new TaskView(role));
        out.add(new Pc(role));
        out.add(new McpManager(role, mcpManager));
        out.add(new Skill(role, skillManager));
        out.add(new Email(role, ctx != null ? ctx.mailService : null));
        // 领导组全员装备与甲方沟通工具 (talk_to_client, 全局互斥)
        if (role != null && LEADERSHIP_GROUP.equals(role.group)) {
            out.add(new Client(role));
        }
        return out;
    }
}
