package com.agent.software.tool;

import com.agent.software.agent.AgentControl;
import com.agent.software.agent.AgentState;
import com.agent.software.agent.AgentTasks;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.agent.task.Task;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.infra.json.JsonCodec;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.sim.clock.ScheduleTable;
import com.agent.software.sim.clock.ShiftCalendar;
import com.agent.software.sim.clock.SimClock;
import com.agent.software.tool.computer.LocalShell;
import com.agent.software.tool.computer.PcToolkit;
import com.agent.software.tool.computer.PodmanShell;
import com.agent.software.tool.computer.Shell;
import com.agent.software.tool.computer.ShellRegistry;
import com.agent.software.tool.computer.SshShell;
import com.agent.software.tool.note.JsonNoteBook;
import com.agent.software.tool.note.MemoryToolkit;
import com.agent.software.tool.note.NoteToolkit;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolkit;
import com.agent.software.tool.task.TaskViewToolkit;
import com.agent.software.tool.time.TimeToolkit;
import com.agent.software.tool.todo.JsonTodoList;
import com.agent.software.tool.todo.TodoToolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具包层测试（迁移自 master {@code tools/toolkits/ToolkitsTest}）。
 *
 * <p>新架构没有 {@code Toolkit.trigger(name, Map)} / {@code Toolkit.snakeCase}：
 * 每个工具包经 {@link Toolkit#instantiate()} 暴露类型化 {@link Tool}（{@link ToolSpec} +
 * {@code invoke(RoleId, Payload)}），错误由 {@link ToolResult#error()} 显式表达。
 * 这里逐个核对工具名、参数 schema 与参数校验/错误路径。
 */
class ToolkitsTest {

    @TempDir
    Path tmp;

    private static final RoleId ME = new RoleId("tester");

    private final JsonCodec json = new JacksonJsonCodec();
    private AppPaths paths;

    @BeforeEach
    void setUp() {
        paths = AppPaths.resolve(new AppConfig.Storage(tmp.resolve("data").toString()));
    }

    // ── 测试辅助 ───────────────────────────────────────────────

    private static List<String> names(Toolkit toolkit) {
        return toolkit.instantiate().stream().map(t -> t.spec().name()).toList();
    }

    private static Tool tool(Toolkit toolkit, String name) {
        return toolkit.instantiate().stream()
                .filter(t -> name.equals(t.spec().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到工具 " + name + "，实际：" + names(toolkit)));
    }

    private static ToolResult call(Toolkit toolkit, String name, Payload arguments) {
        return tool(toolkit, name).invoke(ME, arguments);
    }

    private static ToolResult call(Toolkit toolkit, String name) {
        return call(toolkit, name, Payload.empty());
    }

    /** 每个工具都必须声明 object 根 schema。 */
    private static void assertSchemasAreObjects(Toolkit toolkit) {
        for (Tool t : toolkit.instantiate()) {
            ToolSpec spec = t.spec();
            assertNotNull(spec.schema(), spec.name() + " 缺少参数 schema");
            assertEquals("object", spec.schema().toMap().get("type"), spec.name() + " 的 schema 根类型");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(ToolSpec spec) {
        return (Map<String, Object>) spec.schema().toMap().get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(ToolSpec spec) {
        return (List<String>) spec.schema().toMap().get("required");
    }

    private static String typeOf(ToolSpec spec, String property) {
        Object definition = properties(spec).get(property);
        assertNotNull(definition, spec.name() + " 缺少参数 " + property);
        return String.valueOf(((Map<String, Object>) definition).get("type"));
    }

    /** 记录自身状态变化的假 control（memory 工具包用）。 */
    private static final class FakeControl implements AgentControl {

        AgentState transitioned;
        int closedDay = -1;
        boolean poweredOff;

        @Override
        public void transitionTo(AgentState next) {
            transitioned = next;
        }

        @Override
        public void closeDayConversation(int day) {
            closedDay = day;
        }

        @Override
        public void powerOffComputer() {
            poweredOff = true;
        }
    }

    private static final class EmptyTasks implements AgentTasks {
        @Override
        public List<Task> pending() {
            return List.of();
        }

        @Override
        public List<Task> history(int limit) {
            return List.of();
        }
    }

    private SimClock clock() {
        return new SimClock(ShiftCalendar.of(1.0, 8, 18), LocalDate.of(2026, 1, 1));
    }

    // ── note 工具包 ────────────────────────────────────────────

    @Test
    void note工具包声明与参数schema() {
        NoteToolkit toolkit = new NoteToolkit(new JsonNoteBook(paths, json),
                new ScheduleTable(ShiftCalendar.of(1.0, 8, 18)));
        assertEquals("note", toolkit.id());
        assertEquals(List.of("write_note", "edit_note", "list_notes", "read_note", "delete_note"),
                names(toolkit));
        assertSchemasAreObjects(toolkit);

        ToolSpec write = tool(toolkit, "write_note").spec();
        assertEquals("string", typeOf(write, "title"));
        assertEquals("string", typeOf(write, "content"));
        assertEquals("integer", typeOf(write, "remind_day"));
        assertEquals("integer", typeOf(write, "remind_tick"));
        assertEquals(List.of("title", "content"), required(write));

        ToolSpec update = tool(toolkit, "edit_note").spec();
        assertEquals(List.of("title", "content"), required(update));
        assertEquals(List.of("title"),
                required(tool(toolkit, "read_note").spec()));
        assertEquals(List.of("title"),
                required(tool(toolkit, "delete_note").spec()));
    }

    @Test
    void note工具包增删改查与错误路径() {
        NoteToolkit toolkit = new NoteToolkit(new JsonNoteBook(paths, json),
                new ScheduleTable(ShiftCalendar.of(1.0, 8, 18)));

        assertTrue(call(toolkit, "write_note").error(), "缺 title/content 应报错");
        assertTrue(call(toolkit, "write_note", Payload.of("title", "周报")).error(), "缺 content 应报错");
        assertTrue(call(toolkit, "write_note",
                Payload.of("title", "周报").with("content", "正文").with("remind_tick", 5)).error(),
                "单独给 remind_tick 应报错");

        ToolResult written = call(toolkit, "write_note",
                Payload.of("title", "周报").with("content", "本周总结"));
        assertFalse(written.error(), written.text());
        assertTrue(written.text().contains("已写入笔记"), written.text());

        assertEquals("本周总结", call(toolkit, "read_note", Payload.of("title", "周报")).text());
        assertTrue(call(toolkit, "list_notes").text().contains("周报"));
        assertTrue(call(toolkit, "read_note").error(), "缺 title 应报错");
        assertTrue(call(toolkit, "read_note", Payload.of("title", "不存在")).error());
        assertTrue(call(toolkit, "delete_note", Payload.of("title", "不存在")).error());

        assertFalse(call(toolkit, "edit_note",
                Payload.of("title", "周报").with("content", "更新后的正文")).error());
        assertEquals("更新后的正文", call(toolkit, "read_note", Payload.of("title", "周报")).text());

        assertFalse(call(toolkit, "delete_note", Payload.of("title", "周报")).error());
        assertTrue(call(toolkit, "read_note", Payload.of("title", "周报")).error());
    }

    @Test
    void note工具包注册与取消提醒() {
        ScheduleTable schedule = new ScheduleTable(ShiftCalendar.of(1.0, 8, 18));
        schedule.activateDay(1);
        NoteToolkit toolkit = new NoteToolkit(new JsonNoteBook(paths, json), schedule);

        ToolResult registered = call(toolkit, "write_note",
                Payload.of("title", "提醒笔记").with("content", "别忘了").with("remind_day", 1)
                        .with("remind_tick", 50));
        assertFalse(registered.error(), registered.text());
        assertEquals(1, schedule.list(ME).size(), "提醒应注册进日程表");
        assertTrue(schedule.list(ME).get(0).description().contains("提醒笔记"));

        // 同名覆盖且不带提醒 → 旧提醒被取消（避免幽灵提醒）
        ToolResult overwritten = call(toolkit, "write_note",
                Payload.of("title", "提醒笔记").with("content", "新内容"));
        assertFalse(overwritten.error(), overwritten.text());
        assertTrue(schedule.list(ME).isEmpty(), "覆盖后旧提醒应被取消");
    }

    // ── memory 工具包 ──────────────────────────────────────────

    @Test
    void memory工具包只暴露summary并完成下线动作() {
        JsonNoteBook notes = new JsonNoteBook(paths, json);
        FakeControl control = new FakeControl();
        MemoryToolkit toolkit = new MemoryToolkit(notes, clock(), control);

        assertEquals("memory", toolkit.id());
        assertEquals(List.of("summary"), names(toolkit));
        assertSchemasAreObjects(toolkit);
        assertEquals(List.of("content"), required(tool(toolkit, "summary").spec()));

        assertTrue(call(toolkit, "summary").error(), "缺 content 应报错");
        assertTrue(call(toolkit, "summary", Payload.of("content", "   ")).error());

        ToolResult saved = call(toolkit, "summary", Payload.of("content", "今天完成了登录页开发"));
        assertFalse(saved.error(), saved.text());
        assertTrue(saved.text().contains("已保存"), saved.text());
        assertEquals(AgentState.OFF_DUTY, control.transitioned);
        assertEquals(1, control.closedDay);
        assertTrue(control.poweredOff);
        assertEquals("今天完成了登录页开发", notes.summary(ME, 1).orElseThrow());
    }

    // ── time 工具包 ────────────────────────────────────────────

    @Test
    void time工具包() {
        TimeToolkit toolkit = new TimeToolkit(clock());
        assertEquals("time", toolkit.id());
        assertEquals(List.of("get_time", "take_rest"), names(toolkit));
        assertSchemasAreObjects(toolkit);

        ToolResult now = call(toolkit, "get_time");
        assertFalse(now.error());
        assertTrue(now.text().contains("当前时间"), now.text());
        assertTrue(now.text().contains("2026-01-01"), now.text());
        assertFalse(call(toolkit, "take_rest").error());
    }

    // ── todo 工具包 ────────────────────────────────────────────

    @Test
    void todo工具包增删改查与错误路径() {
        TodoToolkit toolkit = new TodoToolkit(new JsonTodoList(paths, json));
        assertEquals("todo", toolkit.id());
        assertEquals(List.of("todo_add", "todo_list", "todo_update", "todo_delete"), names(toolkit));
        assertSchemasAreObjects(toolkit);
        assertEquals(List.of("title"), required(tool(toolkit, "todo_add").spec()));
        assertEquals(List.of("id", "status"), required(tool(toolkit, "todo_update").spec()));
        assertEquals(List.of("id"), required(tool(toolkit, "todo_delete").spec()));

        assertTrue(call(toolkit, "todo_add").error(), "缺 title 应报错");
        ToolResult added = call(toolkit, "todo_add",
                Payload.of("title", "写周报").with("detail", "本周总结"));
        assertFalse(added.error(), added.text());
        String id = idOf(added.text());

        ToolResult listed = call(toolkit, "todo_list");
        assertTrue(listed.text().contains("写周报"), listed.text());
        assertTrue(listed.text().contains("PENDING"), listed.text());

        ToolResult updated = call(toolkit, "todo_update",
                Payload.of("id", id).with("status", "in_progress"));
        assertFalse(updated.error(), updated.text());
        assertTrue(updated.text().contains("IN_PROGRESS"), updated.text());

        assertTrue(call(toolkit, "todo_update", Payload.of("id", id).with("status", "bogus")).error(),
                "非法状态应报错");
        assertTrue(call(toolkit, "todo_update", Payload.of("id", "ghost").with("status", "COMPLETED")).error());
        assertTrue(call(toolkit, "todo_update", Payload.of("id", id)).error(), "缺 status 应报错");
        assertTrue(call(toolkit, "todo_list", Payload.of("status", "bogus")).error(),
                "非法状态过滤应报错");

        assertFalse(call(toolkit, "todo_delete", Payload.of("id", id)).error());
        assertTrue(call(toolkit, "todo_delete", Payload.of("id", id)).error(), "重复删除应报错");
        assertTrue(call(toolkit, "todo_list").text().contains("当前没有待办"));
    }

    private static String idOf(String text) {
        int start = text.indexOf("[ID=");
        int end = text.indexOf(']', start);
        assertTrue(start >= 0 && end > start, "输出里没有 [ID=...]：" + text);
        return text.substring(start + 4, end);
    }

    // ── task_view 工具包（视图细节见 TaskViewToolkitTest） ──────

    @Test
    void task_view工具包() {
        TaskViewToolkit toolkit = new TaskViewToolkit(new EmptyTasks());
        assertEquals("task_view", toolkit.id());
        assertEquals(List.of("my_tasks"), names(toolkit));
        assertSchemasAreObjects(toolkit);
        ToolResult empty = call(toolkit, "my_tasks");
        assertFalse(empty.error());
        assertTrue(empty.text().contains("当前没有任务"), empty.text());
    }

    // ── pc 工具包 + ShellRegistry ──────────────────────────────

    @Test
    void pc工具包运行命令与状态() {
        ShellRegistry registry = new ShellRegistry(paths, "test-net");
        Shell shell = registry.create(localSpec("tester"));
        assertInstanceOf(LocalShell.class, shell);

        PcToolkit toolkit = new PcToolkit(shell);
        assertEquals("pc", toolkit.id());
        assertEquals(List.of("run_command", "computer_status", "lan_devices", "reboot"), names(toolkit));
        assertSchemasAreObjects(toolkit);
        assertEquals(List.of("command"), required(tool(toolkit, "run_command").spec()));

        ToolResult status = call(toolkit, "computer_status");
        assertFalse(status.error());
        assertTrue(status.text().contains("电脑"), status.text());

        ToolResult run = call(toolkit, "run_command", Payload.of("command", "echo pc-toolkit-ok"));
        assertFalse(run.error(), run.text());
        assertTrue(run.text().contains("pc-toolkit-ok"), run.text());
        assertTrue(call(toolkit, "run_command").error(), "缺 command 应报错");
        assertTrue(call(toolkit, "run_command", Payload.of("command", "   ")).error());

        assertFalse(call(toolkit, "lan_devices").error());
        assertFalse(call(toolkit, "reboot").error(), "本地电脑 reboot 应成功");
    }

    @Test
    void pc工具包未注入shell时返回错误() {
        PcToolkit toolkit = new PcToolkit(null);
        for (String name : List.of("run_command", "computer_status", "lan_devices", "reboot")) {
            ToolResult result = call(toolkit, name);
            assertTrue(result.error(), name + " 未注入 shell 时应返回 error");
        }
    }

    @Test
    void shellRegistry按kind分派并复用实例() {
        ShellRegistry registry = new ShellRegistry(paths, "test-net");

        // 只测 kind 分派，不启动容器 / 不连 SSH
        RoleSpec local = localSpec("local-role");
        RoleSpec podman = specWithComputer("podman-role", "podman", Map.of());
        RoleSpec ssh = specWithComputer("ssh-role", "ssh", Map.of("host", "example.invalid"));
        RoleSpec unknown = specWithComputer("unknown-role", "weird", Map.of());

        assertInstanceOf(LocalShell.class, registry.create(local));
        assertInstanceOf(PodmanShell.class, registry.create(podman));
        assertInstanceOf(SshShell.class, registry.create(ssh));
        assertInstanceOf(LocalShell.class, registry.create(unknown), "未知 kind 应回退本地目录形态");

        Shell first = registry.create(local);
        assertSame(first, registry.create(local), "同一 roleId 必须复用同一实例");
        assertEquals(4, registry.all().size());
    }

    private RoleSpec localSpec(String id) {
        return specWithComputer(id, "local", Map.of(
                "base_dir", tmp.resolve("computers").toString(),
                "drive_dir", tmp.resolve("drive").toString()));
    }

    private static RoleSpec specWithComputer(String id, String kind, Map<String, String> options) {
        return RoleSpec.builder()
                .id(new RoleId(id))
                .name("角色 " + id)
                .username(id)
                .computer(new RoleSpec.ComputerSpec(kind, options))
                .toolkits(Set.of())
                .build();
    }

    // ── JsonSchema（替代 master 的扁平 Map → input_schema 转换） ─

    @Test
    void jsonSchema是工具参数声明的唯一来源() {
        JsonSchema schema = JsonSchema.object()
                .string("title", "标题")
                .integer("remind_tick", "0~60")
                .bool("wait", "是否等待")
                .enumeration("urgency", "紧急度", List.of("LOW", "NORMAL"))
                .required("title", "wait");

        Map<String, Object> map = schema.toMap();
        assertEquals("object", map.get("type"));
        assertEquals(List.of("title", "wait"), map.get("required"));

        Map<?, ?> props = (Map<?, ?>) map.get("properties");
        assertEquals("string", ((Map<?, ?>) props.get("title")).get("type"));
        assertEquals("integer", ((Map<?, ?>) props.get("remind_tick")).get("type"));
        assertEquals("boolean", ((Map<?, ?>) props.get("wait")).get("type"));
        assertEquals(List.of("LOW", "NORMAL"), ((Map<?, ?>) props.get("urgency")).get("enum"));
    }

    @Test
    void 全部内置工具包的工具名集合() {
        // 与 master 相比：note/memory 分离、pc=computer、task_view 只有 my_tasks
        Map<String, List<String>> expected = Map.of(
                "note", List.of("write_note", "edit_note", "list_notes", "read_note", "delete_note"),
                "memory", List.of("summary"),
                "time", List.of("get_time", "take_rest"),
                "todo", List.of("todo_add", "todo_list", "todo_update", "todo_delete"),
                "task_view", List.of("my_tasks"),
                "pc", List.of("run_command", "computer_status", "lan_devices", "reboot"));

        Map<String, Toolkit> toolkits = Map.of(
                "note", new NoteToolkit(new JsonNoteBook(paths, json),
                        new ScheduleTable(ShiftCalendar.of(1.0, 8, 18))),
                "memory", new MemoryToolkit(new JsonNoteBook(paths, json), clock(), new FakeControl()),
                "time", new TimeToolkit(clock()),
                "todo", new TodoToolkit(new JsonTodoList(paths, json)),
                "task_view", new TaskViewToolkit(new EmptyTasks()),
                "pc", new PcToolkit(new LocalShell(ME, new RoleSpec.ComputerSpec("local", Map.of()),
                        paths)));

        Set<String> actualIds = toolkits.keySet();
        assertEquals(expected.keySet(), actualIds);
        for (Map.Entry<String, Toolkit> entry : toolkits.entrySet()) {
            assertEquals(entry.getKey(), entry.getValue().id());
            Set<String> expectedNames = Set.copyOf(expected.get(entry.getKey()));
            Set<String> actualNames = entry.getValue().instantiate().stream()
                    .map(t -> t.spec().name()).collect(Collectors.toSet());
            assertEquals(expectedNames, actualNames, entry.getKey() + " 的工具名集合");
        }
    }
}
