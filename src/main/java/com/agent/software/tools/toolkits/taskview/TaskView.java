package com.agent.software.tools.toolkits.taskview;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/** 任务视图工具包：my_tasks。 */
public class TaskView extends Toolkit {

    public TaskView(Role role) {
        addTool(new MyTasks(role));
    }

    @Override
    public String getDescription() {
        return "Task view: my_tasks (pending events/tasks in my queue)";
    }
}
