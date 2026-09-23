package com.agent.software.tools.toolkits.task;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/**
 * 任务工具包（原 {@code taskview}）：my_tasks / create_task / list_tasks / update_task / delete_task。
 *
 * <p>这里的"任务"是 {@link com.agent.software.event.Task}（就是角色队列里跑的那种任务），
 * 可以排到未来的某个时刻。{@code list_tasks} 看的是"还没到点的排期表"，
 * {@code my_tasks} 看的是"已经派到我队列里的 + 最近的完成/失败历史"。
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
    }

    @Override
    public String getDescription() {
        return "Tasks: my_tasks (my queue + recent history), create_task / list_tasks / update_task / "
                + "delete_task (schedule and manage future tasks; scheduled tasks wake you up when due)";
    }
}
