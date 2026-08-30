package com.maf.scheduler.tools.toolkits;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.Computer;
import com.maf.scheduler.core.NoteStore;
import com.maf.scheduler.core.TimeEventBus;
import com.maf.scheduler.core.TodoStore;
import com.maf.scheduler.core.Types;
import com.maf.scheduler.tools.Toolkit;
import com.maf.scheduler.tools.ToolkitBridge;
import com.maf.scheduler.tools.toolkits.memory.Memory;
import com.maf.scheduler.tools.toolkits.note.Note;
import com.maf.scheduler.tools.toolkits.pc.Pc;
import com.maf.scheduler.tools.toolkits.taskview.TaskView;
import com.maf.scheduler.tools.toolkits.time.Time;
import com.maf.scheduler.tools.toolkits.todo.Todo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板风格工具类 (toolkits.*) 测试:
 * 验证 note 与 memory 分离 / pc = computer / trigger 分发 / AgentRole 桥接.
 */
class ToolkitsTest {

    @TempDir
    Path tmp;

    /** 把应用数据目录指向临时目录 (角色默认 NoteStore 路径可写). */
    @BeforeEach
    void setUp() {
        System.setProperty("AGENTSCHEDULER_DATA_DIR", tmp.resolve("data").toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("AGENTSCHEDULER_DATA_DIR");
    }

    // ── note 工具类 (与 memory 分离) ──────────────────────

    @Test
    void testNoteToolkitCrud() {
        NoteStore store = new NoteStore(tmp.toString(), "tester", null);
        Note note = new Note(store);
        // 工具齐全且不含 summary (记忆内容)
        assertEquals(5, note.getTools().size());
        assertNotNull(note.trigger("write_note", Map.of()));
        assertTrue(note.trigger("write_note", Map.of("name", "周报", "content", "本周小结"))
                .contains("successfully"));
        assertTrue(note.trigger("list_notes", Map.of()).contains("周报"));
        assertEquals("本周小结", note.trigger("read_note", Map.of("name", "周报")));
        assertTrue(note.trigger("edit_note", Map.of("name", "周报", "content", "新版内容"))
                .contains("updated"));
        assertEquals("新版内容", note.trigger("read_note", Map.of("name", "周报")));
        assertTrue(note.trigger("delete_note", Map.of("name", "周报")).contains("deleted"));
        assertTrue(note.trigger("read_note", Map.of("name", "周报")).contains("not found"));
        // 错误参数
        assertTrue(note.trigger("write_note", Map.of()).contains("Error"));
        assertTrue(note.trigger("write_note", Map.of("name", "x", "content", 42)).contains("Error"));
        // 未知工具 → null
        assertNull(note.trigger("no_such_tool", Map.of()));
    }

    @Test
    void testNoteToolkitReminderArgs() {
        NoteStore store = new NoteStore(tmp.toString(), "tester", new TimeEventBus());
        Note note = new Note(store);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", "提醒笔记");
        args.put("content", "内容");
        args.put("reminder_tick", 50);
        String r = note.trigger("write_note", args);
        assertNotNull(r);
        // 提醒注册成功 (TimeEventBus 未 start, 只入调度表)
        assertNotNull(store.getReminder("提醒笔记"));
    }

    // ── memory 工具类 (只含记忆相关内容, 无笔记工具) ──────

    @Test
    void testMemoryToolkitOnlySummary() {
        AgentRole role = AgentRole.builder().name("测试").roleId("tester").build();
        Memory memory = new Memory(role);
        assertEquals(1, memory.getTools().size());
        assertEquals("summary", memory.getTools().get(0).getToolName());
        // summary 保存 → 角色 OFF_DUTY
        String r = memory.trigger("summary", Map.of("content", "今天完成了登录页开发"));
        assertTrue(r.contains("saved") || r.contains("已保存"));
        assertEquals(Types.AgentState.OFF_DUTY, role.state);
        // memory 中不再有笔记工具
        assertNull(memory.trigger("write_note", Map.of()));
    }

    // ── time 工具类 ───────────────────────────────────────

    @Test
    void testTimeToolkit() {
        AgentRole role = AgentRole.builder().name("测试").roleId("tester").build();
        Time time = new Time(role);
        assertTrue(time.trigger("get_time", Map.of()).contains("Tick"));
        role.setState(Types.AgentState.ON_DUTY_BUSY);
        String r = time.trigger("take_rest", Map.of());
        assertTrue(r.contains("休息"));
        assertEquals(Types.AgentState.ON_DUTY_IDLE, role.state);
    }

    // ── todo 工具类 ───────────────────────────────────────

    @Test
    void testTodoToolkitCrud() {
        TodoStore store = new TodoStore("tester", tmp.resolve("todos.json").toString());
        Todo todo = new Todo(store);
        String r1 = todo.trigger("todo_add", Map.of("title", "写周报", "detail", "本周小结"));
        assertTrue(r1.startsWith("todo_add: 已添加待办 [ID="));
        String tid = r1.split("ID=")[1].split("]")[0];
        assertTrue(todo.trigger("todo_list", Map.of()).contains("写周报"));
        assertTrue(todo.trigger("todo_update", Map.of("todo_id", tid, "status", "in_progress"))
                .contains("→ in_progress"));
        assertTrue(todo.trigger("todo_delete", Map.of("todo_id", tid)).contains("已删除"));
        assertTrue(todo.trigger("todo_list", Map.of()).contains("暂无待办"));
        assertTrue(todo.trigger("todo_add", Map.of()).contains("Error"));
    }

    // ── task_view 工具类 ──────────────────────────────────

    @Test
    void testTaskViewToolkit() {
        AgentRole role = AgentRole.builder().name("测试").roleId("tester").build();
        role.addTask(new AgentRole.Task(AgentRole.Urgency.NORMAL.value, "还没开始的任务", "", new LinkedHashMap<>()));
        TaskView taskView = new TaskView(role);
        String r = taskView.trigger("my_tasks", Map.of());
        assertTrue(r.contains("还没开始的任务"));
        assertTrue(taskView.trigger("my_tasks", Map.of("scope", "pending")).contains("待处理"));
    }

    // ── pc 工具类 (pc = computer 工具) ─────────────────────

    @Test
    void testPcToolkit() {
        Computer comp = new Computer.LocalComputer("tester", false,
                tmp.resolve("comp").toString(), tmp.resolve("drive").toString(),
                "测试", "agent", 1100);
        Pc pc = new Pc(comp);
        assertEquals("pc", pc.getName());
        assertTrue(pc.trigger("computer_status", Map.of()).contains("电脑"));
        String out = pc.trigger("run_command", Map.of("command", "echo pc-toolkit-ok"));
        assertTrue(out.contains("pc-toolkit-ok"), "run_command 输出: " + out);
        assertTrue(pc.trigger("run_command", Map.of()).contains("Error"));
        // lan_devices 不依赖个人电脑
        assertNotNull(pc.trigger("lan_devices", Map.of()));
    }

    // ── ToolkitBridge: 新风格 → 旧版 ToolKit → AgentRole 加载 ──

    @Test
    void testAgentRoleLoadsBridgedToolkits() {
        AgentRole role = AgentRole.builder().name("测试").roleId("tester").build();
        int added = role.addToolkit(new Note(role));
        assertTrue(added >= 5, "note 工具数: " + added);
        assertTrue(role.mcpToolNames().contains("write_note"));
        assertTrue(role.mcpToolNames().contains("read_note"));
        // 通过旧版 ToolRegistry 调用
        var result = role.tools().callTool("write_note", Map.of("name", "桥接笔记", "content", "内容"));
        assertTrue(result.content.get(0).text.contains("successfully"));
        // 桥接 schema: 扁平参数说明 → OpenAI input_schema
        var def = role.tools().getToolDef("write_note");
        assertNotNull(def.inputSchema);
        assertEquals("object", def.inputSchema.get("type"));
    }

    @Test
    void testToolkitNameSnakeCase() {
        assertEquals("task_view", Toolkit.snakeCase("TaskView"));
        assertEquals("mcp_manager", Toolkit.snakeCase("McpManager"));
        assertEquals("pc", Toolkit.snakeCase("Pc"));
        assertEquals("hr", Toolkit.snakeCase("Hr"));
        assertEquals("note", Toolkit.snakeCase("Note"));
    }

    @Test
    void testBridgeSchemaConversion() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("name", "the note name");
        flat.put("tick", Map.of("type", "integer", "description", "0~60"));
        Map<String, Object> schema = ToolkitBridge.toInputSchema(flat);
        assertEquals("object", schema.get("type"));
        Map<?, ?> props = (Map<?, ?>) schema.get("properties");
        assertEquals("string", ((Map<?, ?>) props.get("name")).get("type"));
        assertEquals("integer", ((Map<?, ?>) props.get("tick")).get("type"));
    }
}
