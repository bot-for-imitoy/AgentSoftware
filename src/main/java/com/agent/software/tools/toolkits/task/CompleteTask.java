package com.agent.software.tools.toolkits.task;

import com.agent.software.role.Role;
import com.agent.software.store.TaskBoard;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Text;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * complete_task：把**任务看板**上的一条任务标记为已完成。
 *
 * <p>为什么需要它：{@code update_task} / {@code delete_task} 只作用于"还没到点"的排期任务，
 * 而一条任务一旦到点被派出去，它的完成情况是靠 {@code event.Task} 跑完时回写的。如果它没被
 * 单独跑成一条任务（例如到点时人正忙，事件被当作"待处理事件"附在工具结果里**消费**掉了），
 * 看板上那一行就会永远停在 {@code pending} —— 实测跑了一周的仿真里，角色反复看到"这条昨天
 * 17:30 的任务怎么还 pending"，还会花 token 去调查它。有了这个工具，角色可以自己把这类
 * 已经处理完、却没人回写的条目收口。
 *
 * <p>语义：
 * <ul>
 *   <li>只作用于**自己的**看板（{@code list_tasks} 能看到的那份）；</li>
 *   <li>任务**还没到点**时，顺手把它的排期也取消（否则到点会再派一次、白跑一遍）；</li>
 *   <li>可选 {@code note} 会追加到记录的 detail 里，留一行"谁在什么时候为什么收的口"；</li>
 *   <li>对已经 {@code done} 的记录是幂等的（回一句"本来就是 done"）。</li>
 * </ul>
 */
public class CompleteTask extends Tool {

    private static final int TITLE_LIMIT = 80;

    private final Role role;

    public CompleteTask(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "complete_task";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("task_id", "The task_id shown by list_tasks (8-char id or the full id).");
        schema.put("note", "(Optional) one line on how it ended; appended to the board record.");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Mark a task on your task board as done. Use it when a task is finished but still shows "
                + "as pending — for example one that was handed to you inline instead of running as its "
                + "own task, or a reminder you dealt with out of band. If the task is still scheduled "
                + "(not due yet) its schedule is cancelled as well. list_tasks shows the ids.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "complete_task error: role not bound to a system";
        }
        String id = TaskSupport.str(args.get("task_id")).strip();
        if (id.isEmpty()) {
            return "complete_task error: needs a task_id (see list_tasks)";
        }
        TaskBoard board = role.taskBoard();
        TaskBoard.Record rec = board.find(null, id);        // 先看当前基线组
        if (rec == null) {
            rec = board.findAnywhere(id);                   // 再翻所有组
        }
        if (rec == null) {
            return "complete_task error: no task matching '" + id + "' on your task board — "
                    + "list_tasks shows the tasks you created (someone else's board is not visible here)";
        }
        boolean wasDone = "done".equalsIgnoreCase(rec.status);

        // 还没到点就顺带取消排期，免得它到点又派一次
        String cancelled = "";
        for (var t : TaskSupport.scheduledTasks(role.getSystem().getEventBus())) {
            if (t.uuid.equals(rec.id)) {
                if (role.getSystem().getEventBus().cancel(t.uuid)) {
                    cancelled = " Also cancelled its pending schedule (was due "
                            + TaskSupport.describeTime(role.getSystem().getTimeBus(), t.targetTime) + ").";
                }
                break;
            }
        }

        String note = TaskSupport.str(args.get("note")).strip();
        if (!note.isEmpty()) {
            String detail = rec.detail == null ? "" : rec.detail.strip();
            String line = "[" + role.roleId + " " + now() + "] " + note;
            board.update(rec.id, null, detail.isEmpty() ? line : detail + "\n" + line, null, null);
        }
        board.recordStatus(rec.id, "done", rec.tokens);
        role.journal("complete_task " + TaskSupport.shortId(rec.id) + (wasDone ? " (already done)" : ""));

        String title = rec.title == null || rec.title.isBlank() ? "(no title)" : rec.title;
        return "complete_task: [" + TaskSupport.shortId(rec.id) + "] marked done"
                + (wasDone ? " (it already was)" : "")
                + " — \"" + Text.truncate(title.replace('\n', ' '), TITLE_LIMIT) + "\""
                + (rec.target == null || rec.target.isBlank() ? "" : " -> " + rec.target)
                + cancelled
                + (note.isEmpty() ? "" : " Note recorded.");
    }

    private String now() {
        var tb = role.getSystem().getTimeBus();
        return tb == null ? "?" : tb.currentDateTime();
    }
}
