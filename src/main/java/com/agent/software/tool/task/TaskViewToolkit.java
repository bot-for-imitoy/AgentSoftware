package com.agent.software.tool.task;

import com.agent.software.agent.AgentTasks;
import com.agent.software.agent.task.Task;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolkit;

import java.util.List;
import java.util.OptionalInt;

/**
 * 任务视图工具包（id {@code "task_view"}），暴露工具：my_tasks（经由构造期注入的 {@link AgentTasks} 读取本角色的待办与历史任务）。
 *
 * <p>只读视图：待办队列 + 最近已结束任务，每条格式为
 * {@code #序号 [状态/紧急度] 描述（来源）}。
 */
public final class TaskViewToolkit implements Toolkit {

    /** 历史任务的默认条数。 */
    private static final int DEFAULT_LIMIT = 10;

    private final AgentTasks tasks;

    public TaskViewToolkit(AgentTasks tasks) {
        this.tasks = tasks;
    }

    @Override
    public String id() {
        return "task_view";
    }

    @Override
    public List<Tool> instantiate() {
        return List.of(new MyTasks());
    }

    /** my_tasks：本角色的待办队列与最近历史任务。 */
    private final class MyTasks implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("my_tasks", "查看分配给我的任务：待办队列与最近已结束的任务历史。",
                    JsonSchema.object()
                            .integer("limit", "（可选）历史任务条数，默认 10；<=0 表示全部"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            OptionalInt limitArg = arguments.intValue("limit");
            int limit = limitArg.orElse(DEFAULT_LIMIT);

            List<Task> pending = tasks.pending();
            List<Task> history = tasks.history(limit);
            if (pending.isEmpty() && history.isEmpty()) {
                return ToolResult.ok("my_tasks: 当前没有任务。");
            }

            StringBuilder sb = new StringBuilder("my_tasks:");
            sb.append("\n待处理任务（").append(pending.size()).append("）：");
            if (pending.isEmpty()) {
                sb.append("无");
            } else {
                int index = 1;
                for (Task task : pending) {
                    sb.append("\n").append(line(index++, task));
                }
            }
            sb.append("\n最近任务（").append(history.size()).append("）：");
            if (history.isEmpty()) {
                sb.append("无");
            } else {
                int index = 1;
                for (Task task : history) {
                    sb.append("\n").append(line(index++, task));
                }
            }
            return ToolResult.ok(sb.toString());
        }

        /** 单条：{@code #序号 [状态/紧急度] 描述（来源）}。 */
        private String line(int index, Task task) {
            return "#" + index + " [" + task.status() + "/" + task.urgency() + "] "
                    + Text.truncate(Text.squashWhitespace(task.description()), 120)
                    + "（" + task.source() + "）";
        }
    }
}
