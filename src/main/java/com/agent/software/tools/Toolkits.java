package com.agent.software.tools;

import com.agent.software.role.Role;
import com.agent.software.services.MailService;
import com.agent.software.store.ToolkitConfig;
import com.agent.software.tools.toolkits.client.Client;
import com.agent.software.tools.toolkits.email.Email;
import com.agent.software.tools.toolkits.mcp.MCPManager;
import com.agent.software.tools.toolkits.mcp.McpManager;
import com.agent.software.tools.toolkits.pc.Pc;
import com.agent.software.tools.toolkits.skill.Skill;
import com.agent.software.tools.toolkits.skill.SkillManager;
import com.agent.software.tools.toolkits.staffing.StaffingToolkit;
import com.agent.software.tools.toolkits.taskview.TaskView;
import com.agent.software.tools.toolkits.time.Time;

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
            List.of("time", "task_view", "pc", "mcp_manager", "skill", "email");

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
                case "task_view" -> out.add(new TaskView(role));
                case "pc" -> out.add(new Pc(role));
                case "mcp_manager" -> out.add(new McpManager(role, mcp));
                case "skill", "skill_manager" -> out.add(new Skill(role, skill));
                case "email" -> out.add(new Email(role, mail));
                case "client" -> out.add(new Client(role));
                case "staffing" -> out.add(new StaffingToolkit(role));
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
