package com.agent.software.tools;

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
 * Toolkit registry (the Java counterpart of the Python python_tools/__init__.py).
 *
 * DEFAULT_TOOLKITS (toolkits auto-assembled for roles, all template-style toolkits.* implementations):
 *   memory / note / time / todo / task_view / pc / mcp_manager /
 *   skill_manager / email (hermes is disabled by default, consistent with the Python version).
 *
 * Notes:
 *   - The note tool has been split out of memory: memory only keeps memory-related content (summary),
 *     note operations (write_note/edit_note/list_notes/read_note/delete_note) live in
 *     toolkits.note.Note.
 *   - pc is the computer tool (toolkits.pc.Pc): run_command / computer_status /
 *     lan_devices / reboot.
 *
 * DEFAULT_MCP_GROUPS: when a role joins/starts, the MCP tools of the group are automatically installed on the personal computer.
 */
public final class Toolkits {

    private Toolkits() {
    }

    /** Globally shared MCP manager. */
    private static final MCPManager MCP_MANAGER = new MCPManager();

    /** Globally shared skill library manager. */
    private static final SkillManager SKILL_MANAGER = new SkillManager();

    /** Default MCP tool group: file operation MCP tools. */
    public static final List<String> DEFAULT_MCP_GROUPS = List.of("file_ops");

    /** Leadership group name (corresponds to the group field in role_templates.json). */
    public static final String LEADERSHIP_GROUP = "Leadership Group";

    public static MCPManager getMcpManager() {
        return MCP_MANAGER;
    }

    public static SkillManager getSkillManager() {
        return SKILL_MANAGER;
    }

    /**
     * Default toolkit registry: when a role is added to an AgentSystem (autoToolkits=true)
     * the toolkits are loaded one by one automatically (RolePool.setupRole calls this, passing the concrete role so the toolkits can bind).
     * Each call returns a new independent template-style toolkit instance (AgentRole.addToolkit(Toolkit)
     * registers directly into the ToolRegistry exposed to the LLM).
     *
     * <p>Collaboration objects used by the toolkits (MCP/skills/mailbox/conversation lock) prefer the per-system
     * instance of the {@link com.agent.software.AgentSystem} the role belongs to; standalone roles not bound to a system
     * fall back to the process-level default singletons of this class (legacy behavior).
     */
    public static List<Toolkit> defaultToolkits(AgentRole role) {
        MCPManager mcpManager = role != null ? role.mcpManager() : MCP_MANAGER;
        SkillManager skillManager = role != null ? role.skillManager() : SKILL_MANAGER;
        List<Toolkit> out = new ArrayList<>();
        out.add(new Memory(role));
        out.add(new Note(role));
        out.add(new Time(role));
        out.add(new Todo(role));
        out.add(new TaskView(role));
        out.add(new Pc(role));
        out.add(new McpManager(role, mcpManager));
        out.add(new Skill(role, skillManager));
        out.add(new Email(role, role != null ? role.mailService() : null));
        // all leadership group members get the tool for communicating with the client (talk_to_client, globally exclusive)
        if (role != null && LEADERSHIP_GROUP.equals(role.group)) {
            out.add(new Client(role));
        }
        return out;
    }
}
