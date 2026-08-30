package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.ToolRegistry.ToolKit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 工具类绑定分发表 (Python 版 get_toolkit_binders 的 Java 对应物).
 *
 * toolkit.name → 绑定函数 (toolkit, role). 绑定函数只把角色引用放进
 * toolkit 的 bindings, 工具 handler 在调用时才读取, 跨线程安全.
 */
public final class ToolkitBinders {

    private ToolkitBinders() {
    }

    private static volatile Map<String, BiConsumer<ToolKit, AgentRole>> cache = null;

    /** 返回工具类绑定分发表 (惰性初始化). */
    public static Map<String, BiConsumer<ToolKit, AgentRole>> getBinders() {
        if (cache == null) {
            synchronized (ToolkitBinders.class) {
                if (cache == null) {
                    Map<String, BiConsumer<ToolKit, AgentRole>> m = new LinkedHashMap<>();
                    m.put("memory", (tk, role) -> MemoryToolkit.bindStoreToToolkit(tk, role.noteStore(), role));
                    m.put("time", (tk, role) -> TimeToolkit.bindTimeToToolkit(tk, role.timeManager(), role));
                    m.put("mcp_manager", (tk, role) -> MCPManagerToolkit.bindMcpManagerToToolkit(tk, role));
                    m.put("skill_manager", (tk, role) -> SkillToolkit.bindRoleToToolkit(tk, role));
                    m.put("hr", (tk, role) -> HrToolkit.bindRoleToToolkit(tk, role));
                    m.put("computer", (tk, role) -> ComputerToolkit.bindComputerToToolkit(tk, role));
                    m.put("email", (tk, role) -> EmailToolkit.bindEmailToToolkit(tk, role));
                    m.put("todo", (tk, role) -> TodoToolkit.bindTodoToToolkit(tk, role.todoStore()));
                    m.put("task_view", (tk, role) -> TaskViewToolkit.bindRoleToToolkit(tk, role));
                    m.put("hermes", (tk, role) -> HermesToolkit.bindHermesToToolkit(tk, role));
                    m.put("client", (tk, role) -> tk.bind("role", role));
                    m.put("communication", (tk, role) -> tk.bind("role", role));
                    cache = m;
                }
            }
        }
        return cache;
    }
}
