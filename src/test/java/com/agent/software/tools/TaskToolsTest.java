package com.agent.software.tools;

import com.agent.software.AgentSystem;
import com.agent.software.event.Task;
import com.agent.software.io.WebInput;
import com.agent.software.role.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** task 工具包：排期任务的增删改查（create / list / update / delete / my_tasks）。 */
class TaskToolsTest {

    private static final Pattern SHORT_ID = Pattern.compile("\\[([0-9a-f]{8})\\]");

    private static Toolkit toolkit(Role role) {
        return new com.agent.software.tools.toolkits.task.Task(role);
    }

    private static String shortIdOf(String listing) {
        Matcher m = SHORT_ID.matcher(listing);
        assertTrue(m.find(), "listing has no task id: " + listing);
        return m.group(1);
    }

    @Test
    void toolkitExposesTheCrudTools(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            List<String> names = toolkit(ceo).getTools().stream().map(Tool::getToolName).sorted().toList();
            assertEquals(List.of("complete_task", "create_task", "delete_task", "list_tasks", "my_tasks",
                    "task_group_list", "task_group_switch", "update_task"), names);
        } finally {
            system.stop();
        }
    }

    @Test
    void createListUpdateDelete(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            Toolkit task = toolkit(ceo);
            assertTrue(task.trigger("list_tasks", Map.of()).contains("(empty)"), "看板一开始是空的");

            String created = task.trigger("create_task",
                    Map.of("content", "write the SRS", "in_minutes", "90", "priority", "HIGH"));
            assertTrue(created.contains("scheduled task"), created);
            assertTrue(created.contains("group default"), created);
            assertEquals(1, system.getEventBus().scheduled().size());
            Task scheduled = (Task) system.getEventBus().scheduled().get(0);
            assertEquals(Task.PENDING, scheduled.status);
            assertEquals("CEO", scheduled.targetRoleId);
            assertEquals(5_400, scheduled.targetTime, "now(=0) + 90 分钟");

            String listed = task.trigger("list_tasks", Map.of());
            assertTrue(listed.contains("write the SRS"), listed);
            assertTrue(listed.contains("pending"), listed);
            assertTrue(listed.contains("group default"), listed);

            String updated = task.trigger("update_task",
                    Map.of("task_id", shortIdOf(listed), "content", "write the SRS v2", "in_minutes", "30"));
            assertTrue(updated.contains("updated"), updated);
            assertEquals(1, system.getEventBus().scheduled().size(), "改时间不能把任务弄丢或弄重");
            Task after = (Task) system.getEventBus().scheduled().get(0);
            assertEquals("write the SRS v2", after.content);
            assertEquals(1_800, after.targetTime);

            String deleted = task.trigger("delete_task", Map.of("task_id", shortIdOf(listed)));
            assertTrue(deleted.contains("cancelled"), deleted);
            assertTrue(system.getEventBus().scheduled().isEmpty());
            assertTrue(system.getEventBus().nextDue() == null, "删掉后不应再有后续工作");
            assertTrue(task.trigger("list_tasks", Map.of()).contains("(empty)"), "看板上的记录也要一并删掉");
        } finally {
            system.stop();
        }
    }

    /** 任务按组存放：不写 group 就走基线组，切换基线后新任务进新组，旧组不受影响。 */
    @Test
    void taskGroupsAreIsolatedAndSwitchable(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            Toolkit task = toolkit(ceo);
            assertTrue(task.trigger("task_group_list", Map.of()).contains("baseline = default"));

            task.trigger("create_task", Map.of("content", "plan S1", "in_minutes", "30", "group", "s1"));
            assertTrue(task.trigger("create_task",
                    Map.of("content", "plan S2", "in_minutes", "30")).contains("group default"));

            String groups = task.trigger("task_group_list", Map.of());
            assertTrue(groups.contains("default (1 task(s)"), groups);
            assertTrue(groups.contains("s1 (1 task(s)"), groups);

            String switched = task.trigger("task_group_switch", Map.of("name", "s1"));
            assertTrue(switched.contains("baseline is now 's1'"), switched);
            // 基线切到 s1 之后，不带 group 的新任务进 s1
            assertTrue(task.trigger("create_task", Map.of("content", "plan S1 detail", "in_minutes", "45"))
                    .contains("group s1"));
            assertTrue(task.trigger("list_tasks", Map.of()).contains("plan S1 detail"));
            assertFalse(task.trigger("list_tasks", Map.of()).contains("plan S2"),
                    "基线组之外的组不该出现在默认列表里");
            assertTrue(task.trigger("list_tasks", Map.of("group", "default")).contains("plan S2"));
        } finally {
            system.stop();
        }
    }

    /** 任务跑完后，完成情况要实时写回派活人的看板。 */
    @Test
    void completedTaskStatusIsWrittenBackToTheBoard(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            ceo.setLlm(new FakeLlm());
            Toolkit task = toolkit(ceo);
            assertTrue(task.trigger("create_task",
                    Map.of("content", "follow up", "in_minutes", "1", "group", "release"))
                    .contains("group release"));

            system.getTimeBus().setNow(60);   // 到点 → 投递 → worker 跑掉
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline
                    && !task.trigger("list_tasks", Map.of("group", "release")).contains("done")) {
                Thread.sleep(50);
            }
            String listed = task.trigger("list_tasks", Map.of("group", "release"));
            assertTrue(listed.contains("done"), listed);
            assertTrue(listed.contains("follow up"), listed);

            // 换个看板实例从磁盘复核（实时保存）
            var reread = new com.agent.software.store.TaskBoard(dir.resolve("task_groups"), "CEO");
            assertEquals("done", reread.items("release").get(0).status);
            assertTrue(task.trigger("list_tasks", Map.of("group", "release", "status", "pending"))
                    .contains("(empty)"));
        } finally {
            system.stop();
        }
    }

    /**
     * complete_task：把看板上一条还挂着 pending 的记录收口。
     *
     * <p>这就是"到点时人正忙、事件被当待处理事件消费掉"的那种任务的出口 ——
     * update_task / delete_task 都只认"还没到点"的排期任务，对它们无能为力。
     */
    @Test
    void completeTaskClosesABoardRecord(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            Toolkit task = toolkit(ceo);
            assertTrue(task.trigger("create_task",
                    Map.of("content", "chase the client", "in_minutes", "30")).contains("scheduled task"));
            String id = shortIdOf(task.trigger("list_tasks", Map.of()));

            String done = task.trigger("complete_task",
                    Map.of("task_id", id, "note", "answered by mail, nothing left to do"));
            assertTrue(done.contains("marked done"), done);
            assertTrue(done.contains("cancelled its pending schedule"), done);
            assertTrue(system.getEventBus().scheduled().isEmpty(), "还没到点的任务要连排期一起撤掉");

            String listed = task.trigger("list_tasks", Map.of());
            assertTrue(listed.contains("done"), listed);
            assertFalse(listed.contains("pending"), listed);
            assertTrue(task.trigger("list_tasks", Map.of("status", "pending")).contains("(empty)"));

            // 幂等：再收一次不报错
            assertTrue(task.trigger("complete_task", Map.of("task_id", id)).contains("already was"),
                    "重复标记应该是幂等的");

            // note 要落进 detail 并实时存盘
            var reread = new com.agent.software.store.TaskBoard(dir.resolve("task_groups"), "CEO");
            assertTrue(reread.items("default").get(0).detail.contains("answered by mail"),
                    reread.items("default").get(0).detail);

            assertTrue(task.trigger("complete_task", Map.of("task_id", "deadbeef"))
                    .contains("no task matching"), "找不到的 id 要明确报错");
            assertTrue(task.trigger("complete_task", Map.of()).contains("needs a task_id"));
        } finally {
            system.stop();
        }
    }

    /** 参数校验：坏参数必须是"工具报错文本"，不能抛异常、不能悄悄当成合法。 */
    @Test
    void rejectsBadArguments(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Toolkit task = toolkit(system.getRolePool().find("CEO"));
            assertTrue(task.trigger("create_task", Map.of("in_minutes", "5")).contains("needs task content"));
            assertTrue(task.trigger("create_task", Map.of("content", "x")).contains("needs a time"));
            assertTrue(task.trigger("create_task", Map.of("content", "x", "in_minutes", "0"))
                    .contains("must be a positive"));
            assertTrue(task.trigger("create_task", Map.of("content", "x", "in_minutes", "abc"))
                    .contains("not an integer"));
            assertTrue(task.trigger("create_task", Map.of("content", "x", "day", "2", "tick", "99999"))
                    .contains("tick must be between"));
            assertTrue(task.trigger("create_task", Map.of("content", "x", "day", "1", "tick", "0"))
                    .contains("already in the past"));
            assertTrue(task.trigger("create_task", Map.of("content", "x", "in_minutes", "5", "target", "nobody"))
                    .contains("no active role"));
            assertTrue(task.trigger("create_task", Map.of("content", "x", "in_minutes", "5", "target", "architect"))
                    .contains("no active role"), "名单里没进组的员工是假死的，不能派活");
            assertTrue(task.trigger("update_task", Map.of("task_id", "deadbeef", "content", "x"))
                    .contains("no scheduled task"));
            assertTrue(task.trigger("delete_task", Map.of()).contains("needs a task_id"));
            assertTrue(task.trigger("my_tasks", Map.of("scope", "nonsense")).contains("scope must be"));
        } finally {
            system.stop();
        }
    }

    @Test
    void onlyTheCreatorAssigneeOrManagementMayChangeATask(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role cto = system.getRolePool().find("CTO");
            Role architect = system.getStaffing().draftIn("architect");
            String created = toolkit(cto).trigger("create_task",
                    Map.of("content", "design the schema", "in_minutes", "10", "target", "architect"));
            assertTrue(created.contains("scheduled task"), created);
            String id = shortIdOf(toolkit(cto).trigger("list_tasks", Map.of()));

            // 被指派者可以改自己的任务
            assertTrue(toolkit(architect).trigger("update_task", Map.of("task_id", id, "content", "v2"))
                    .contains("updated"));
            // 与此无关的同组角色不能改（architect 改完仍归属 CTO -> architect）
            Role reviewer = system.getStaffing().draftIn("reviewer");
            String denied = toolkit(reviewer).trigger("delete_task", Map.of("task_id", id));
            assertTrue(denied.contains("only its creator"), denied);
            assertFalse(system.getEventBus().scheduled().isEmpty());
            // 管理组可以改
            assertTrue(toolkit(cto).trigger("delete_task", Map.of("task_id", id)).contains("cancelled"));
        } finally {
            system.stop();
        }
    }

    @Test
    void crossTeamAssignmentNeedsManagement(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role cto = system.getRolePool().find("CTO");
            Role architect = system.getStaffing().draftIn("architect");
            assertTrue(toolkit(cto).trigger("create_task",
                            Map.of("content", "design the schema", "in_minutes", "10", "target", "architect"))
                    .contains("scheduled task"), "管理组可以跨组派活");
            String denied = toolkit(architect).trigger("create_task",
                    Map.of("content", "review my design", "in_minutes", "10", "target", "CTO"));
            assertTrue(denied.contains("cannot assign tasks"), denied);
        } finally {
            system.stop();
        }
    }

    @Test
    void scheduledTaskWakesTheRoleAndShowsUpInHistory(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            ceo.setLlm(new FakeLlm());
            Toolkit task = toolkit(ceo);

            // 排 1 分钟后到点的任务（1 分钟 = 60 tick），把时钟推过去让它投递
            assertTrue(task.trigger("create_task", Map.of("content", "follow up", "in_minutes", "1"))
                    .contains("scheduled task"));
            assertTrue(task.trigger("list_tasks", Map.of()).contains("follow up"));

            system.getTimeBus().setNow(60);   // 到点 → eventBus.tick 投递 → worker 跑掉

            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && ceo.taskHistory(1).isEmpty()) {
                Thread.sleep(20);
            }
            List<Task> history = ceo.taskHistory(5);
            assertFalse(history.isEmpty(), "跑完的任务要进历史（否则静默失败没人知道）");
            assertEquals(Task.DONE, history.get(history.size() - 1).status);
            assertTrue(system.getEventBus().scheduled().isEmpty(), "到点后应离开排期表");
            String mine = task.trigger("my_tasks", Map.of());
            assertTrue(mine.contains("Recent tasks"), mine);
            assertTrue(mine.contains("follow up"), mine);
        } finally {
            system.stop();
        }
    }

    /** 立即返回的假 LLM，避免测试里真的发 HTTP。 */
    private static final class FakeLlm extends com.agent.software.llm.LLM {
        @Override
        public String getModel() {
            return "fake";
        }

        @Override
        public String getEndpoint() {
            return "fake";
        }

        @Override
        public com.agent.software.llm.Response request() {
            return new com.agent.software.llm.Response("ok", "", List.of(), 0);
        }
    }
}
