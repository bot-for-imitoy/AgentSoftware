package com.agent.software.tools;

import com.agent.software.role.Role;
import com.agent.software.services.MailService;
import com.agent.software.store.ToolkitConfig;
import com.agent.software.tools.toolkits.client.Client;
import com.agent.software.tools.toolkits.email.Email;
import com.agent.software.tools.toolkits.hr.Hr;
import com.agent.software.tools.toolkits.mcp.MCPManager;
import com.agent.software.tools.toolkits.mcp.McpManager;
import com.agent.software.tools.toolkits.memory.Memory;
import com.agent.software.tools.toolkits.note.Note;
import com.agent.software.tools.toolkits.pc.Pc;
import com.agent.software.tools.toolkits.skill.Skill;
import com.agent.software.tools.toolkits.skill.SkillManager;
import com.agent.software.tools.toolkits.staffing.StaffingToolkit;
import com.agent.software.tools.toolkits.talk.Talk;
import com.agent.software.tools.toolkits.task.Task;
import com.agent.software.tools.toolkits.time.Time;
import com.agent.software.tools.toolkits.todo.Todo;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认工具集工厂（纯工厂，无 static 单例）。
 *
 * <p>清单优先从 {@link ToolkitConfig} 读；没有配置时用内置默认值。
 * 之后按角色额外追加：管理组 → talk_to_client；COO → 调度工具。
 */
public final class Toolkits {

    public static final List<String> DEFAULT_MCP_GROUPS = List.of("file_ops");
    public static final String LEADERSHIP_GROUP = "Leadership Group";

    private static final List<String> DEFAULT_NAMES =
            List.of("time", "task", "note", "todo", "memory", "talk", "pc", "mcp_manager", "skill", "email");

    private Toolkits() {
    }

    public static List<Toolkit> defaults(Role role, ToolkitConfig config,
                                         MailService mail, MCPManager mcp, SkillManager skill) {
        List<String> names = config == null || role == null
                ? DEFAULT_NAMES
                : config.defaultsFor(role.roleId, role.group);
        if (names == null || names.isEmpty()) {
            names = DEFAULT_NAMES;
        }
        List<Toolkit> out = new ArrayList<>();
        for (String name : names) {
            switch (name) {
                case "time" -> out.add(new Time(role));
                // "task_view" 是旧名字，配置里可能还留着，一起认
                case "task", "task_view" -> out.add(new Task(role));
                case "note" -> out.add(new Note(role));
                case "todo" -> out.add(new Todo(role));
                case "talk" -> out.add(new Talk(role));
                case "memory" -> out.add(new Memory(role));
                case "pc" -> out.add(new Pc(role));
                case "mcp_manager" -> out.add(new McpManager(role, mcp));
                case "skill", "skill_manager" -> out.add(new Skill(role, skill));
                case "email" -> out.add(new Email(role, mail));
                case "client" -> out.add(new Client(role));
                case "staffing" -> out.add(new StaffingToolkit(role));
                case "hr" -> out.add(new Hr(role));
                default -> {
                    // 未知名字忽略
                }
            }
        }
        if (role != null && LEADERSHIP_GROUP.equals(role.group) && !has(out, Client.class)) {
            out.add(new Client(role));
        }
        if (role != null && "COO".equals(role.roleId) && !has(out, StaffingToolkit.class)) {
            out.add(new StaffingToolkit(role));
        }
        if (role != null && "HR".equals(role.roleId) && !has(out, Hr.class)) {
            out.add(new Hr(role));
        }
        return out;
    }

    private static boolean has(List<Toolkit> toolkits, Class<? extends Toolkit> type) {
        for (Toolkit t : toolkits) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
