package com.agent.software.tools.toolkits.task;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/**
 * 任务工具包（原 {@code taskview}）：my_tasks / create_task / list_tasks / update_task / delete_task
 * + task_group_list / task_group_switch（任务按**组**存放，可切换基线组）。
 *
 * <p>这里的"任务"是 {@link com.agent.software.event.Task}（就是角色队列里跑的那种任务），
 * 可以排到未来的某个时刻。{@code list_tasks} 看的是**任务看板**（我派出去的活，按组存放、
 * 含完成状态），{@code my_tasks} 看的是"已经派到我队列里的 + 最近的完成/失败历史"。
 *
 * <p>注意：本类简单名与 {@code event.Task} 重名（包 {@code task} → 工具包名 {@code task}），
 * 同包的工具类通过单类型 import 让 {@code Task} 指向事件类型（JLS 6.4.1，import 遮蔽同包同名类）。
 */
public class Task extends Toolkit {

    public Task(Role role) {
        addTool(new MyTasks(role));
        addTool(new CreateTask(role));
        addTool(new ListTasks(role));
        addTool(new UpdateTask(role));
        addTool(new DeleteTask(role));
        addTool(new CompleteTask(role));
        addTool(new TaskGroupList(role));
        addTool(new TaskGroupSwitch(role));
    }

    @Override
    public String getDescription() {
        return "Tasks: my_tasks (my queue + recent history), create_task / list_tasks / update_task / "
                + "delete_task / complete_task (schedule and manage future tasks in groups; scheduled "
                + "tasks wake the assignee when due) + task_group_list / task_group_switch (pick the "
                + "baseline group)";
    }
}
