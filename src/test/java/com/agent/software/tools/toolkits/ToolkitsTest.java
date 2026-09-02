package com.agent.software.tools.toolkits;

import com.agent.software.role.AgentRole;
import com.agent.software.computers.Computer;
import com.agent.software.store.NoteStore;
import com.agent.software.event.TimeEventBus;
import com.agent.software.store.TodoStore;
import com.agent.software.core.Types;
import com.agent.software.tools.Tool;
import com.agent.software.tools.Toolkit;
import com.agent.software.tools.toolkits.memory.Memory;
import com.agent.software.tools.toolkits.note.Note;
import com.agent.software.tools.toolkits.pc.Pc;
import com.agent.software.tools.toolkits.taskview.TaskView;
import com.agent.software.tools.toolkits.time.Time;
import com.agent.software.tools.toolkits.todo.Todo;
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
 * Template-style toolkit (toolkits.*) tests:
 * verifies note/memory separation, pc = computer, trigger dispatch, and AgentRole loading.
 */
class ToolkitsTest {

    @TempDir
    Path tmp;

    /** Points the app data dir at a temp dir (PathManager prefix = AGENTCOMPANY; the default role storage paths become writable). */
    @BeforeEach
    void setUp() {
        System.setProperty("AGENTCOMPANY_DATA_DIR", tmp.resolve("data").toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("AGENTCOMPANY_DATA_DIR");
    }

    // ── note toolkit (separate from memory) ───────────────────

    @Test
    void testNoteToolkitCrud() {
        NoteStore store = new NoteStore(tmp.toString(), "tester", null);
        Note note = new Note(store);
        // all tools present, no summary (memory content)
        assertEquals(5, note.getTools().size());
        assertNotNull(note.trigger("write_note", Map.of()));
        assertTrue(note.trigger("write_note", Map.of("name", "Weekly report", "content", "This week's summary"))
                .contains("successfully"));
        assertTrue(note.trigger("list_notes", Map.of()).contains("Weekly_report"));
        assertEquals("This week's summary", note.trigger("read_note", Map.of("name", "Weekly report")));
        assertTrue(note.trigger("edit_note", Map.of("name", "Weekly report", "content", "Updated content"))
                .contains("updated"));
        assertEquals("Updated content", note.trigger("read_note", Map.of("name", "Weekly report")));
        assertTrue(note.trigger("delete_note", Map.of("name", "Weekly report")).contains("deleted"));
        assertTrue(note.trigger("read_note", Map.of("name", "Weekly report")).contains("not found"));
        // invalid args
        assertTrue(note.trigger("write_note", Map.of()).contains("Error"));
        assertTrue(note.trigger("write_note", Map.of("name", "x", "content", 42)).contains("Error"));
        // unknown tool → null
        assertNull(note.trigger("no_such_tool", Map.of()));
    }

    @Test
    void testNoteToolkitReminderArgs() {
        NoteStore store = new NoteStore(tmp.toString(), "tester", new TimeEventBus());
        Note note = new Note(store);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", "Reminder note");
        args.put("content", "Content");
        args.put("reminder_tick", 50);
        String r = note.trigger("write_note", args);
        assertNotNull(r);
        // reminder registered (TimeEventBus not started; only added to the schedule table)
        assertNotNull(store.getReminder("Reminder note"));
    }

    // ── memory toolkit (memory-related only, no note tools) ───

    @Test
    void testMemoryToolkitOnlySummary() {
        AgentRole role = AgentRole.builder().name("Test").roleId("tester").build();
        Memory memory = new Memory(role);
        assertEquals(1, memory.getTools().size());
        assertEquals("summary", memory.getTools().get(0).getToolName());
        // summary saved → role OFF_DUTY
        String r = memory.trigger("summary", Map.of("content", "Finished the login page development today"));
        assertTrue(r.contains("saved"));
        assertEquals(Types.AgentState.OFF_DUTY, role.state);
        // memory no longer has note tools
        assertNull(memory.trigger("write_note", Map.of()));
    }

    // ── time toolkit ──────────────────────────────────────────

    @Test
    void testTimeToolkit() {
        AgentRole role = AgentRole.builder().name("Test").roleId("tester").build();
        Time time = new Time(role);
        assertTrue(time.trigger("get_time", Map.of()).contains("Tick"));
        role.setState(Types.AgentState.ON_DUTY_BUSY);
        String r = time.trigger("take_rest", Map.of());
        assertTrue(r.contains("rest"));
        assertEquals(Types.AgentState.ON_DUTY_IDLE, role.state);
    }

    // ── todo toolkit ──────────────────────────────────────────

    @Test
    void testTodoToolkitCrud() {
        TodoStore store = new TodoStore("tester", tmp.resolve("todos.json").toString());
        Todo todo = new Todo(store);
        String r1 = todo.trigger("todo_add", Map.of("title", "Write weekly report", "detail", "This week's summary"));
        assertTrue(r1.startsWith("todo_add: Added todo [ID="));
        String tid = r1.split("ID=")[1].split("]")[0];
        assertTrue(todo.trigger("todo_list", Map.of()).contains("Write weekly report"));
        assertTrue(todo.trigger("todo_update", Map.of("todo_id", tid, "status", "in_progress"))
                .contains("→ in_progress"));
        assertTrue(todo.trigger("todo_delete", Map.of("todo_id", tid)).contains("Todo deleted"));
        assertTrue(todo.trigger("todo_list", Map.of()).contains("no todos"));
        assertTrue(todo.trigger("todo_add", Map.of()).contains("Error"));
    }

    // ── task_view toolkit ─────────────────────────────────────

    @Test
    void testTaskViewToolkit() {
        AgentRole role = AgentRole.builder().name("Test").roleId("tester").build();
        role.addTask(new AgentRole.Task(AgentRole.Urgency.NORMAL.value, "Task not started yet", "", new LinkedHashMap<>()));
        TaskView taskView = new TaskView(role);
        String r = taskView.trigger("my_tasks", Map.of());
        assertTrue(r.contains("Task not started yet"));
        assertTrue(taskView.trigger("my_tasks", Map.of("scope", "pending")).contains("Pending"));
    }

    // ── pc toolkit (pc = computer tool) ──────────────────────

    @Test
    void testPcToolkit() {
        Computer comp = new Computer.LocalComputer("tester", false,
                tmp.resolve("comp").toString(), tmp.resolve("drive").toString(),
                "Test", "agent", 1100);
        Pc pc = new Pc(comp);
        assertEquals("pc", pc.getName());
        assertTrue(pc.trigger("computer_status", Map.of()).contains("Computer"));
        String out = pc.trigger("run_command", Map.of("command", "echo pc-toolkit-ok"));
        assertTrue(out.contains("pc-toolkit-ok"), "run_command output: " + out);
        assertTrue(pc.trigger("run_command", Map.of()).contains("Error"));
        // lan_devices does not depend on the personal computer
        assertNotNull(pc.trigger("lan_devices", Map.of()));
    }

    // ── Template-style toolkits → ToolRegistry → AgentRole loading ─

    @Test
    void testAgentRoleLoadsTemplateToolkits() {
        AgentRole role = AgentRole.builder().name("Test").roleId("tester").build();
        int added = role.addToolkit(new Note(role));
        assertTrue(added >= 5, "note tool count: " + added);
        assertTrue(role.mcpToolNames().contains("write_note"));
        assertTrue(role.mcpToolNames().contains("read_note"));
        // call through the ToolRegistry
        var result = role.tools().callTool("write_note", Map.of("name", "Template note", "content", "Content"));
        assertTrue(result.content.get(0).text.contains("successfully"));
        // input_schema: flat parameter descriptions → OpenAI style
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
    void testToolInputSchemaConversion() {
        Tool stub = new Tool() {
            @Override
            public String getToolName() {
                return "stub";
            }

            @Override
            public Map<String, Object> getSchema() {
                Map<String, Object> flat = new LinkedHashMap<>();
                flat.put("name", "the note name");
                flat.put("tick", Map.of("type", "integer", "description", "0~60"));
                return flat;
            }

            @Override
            public String handler(Map<String, Object> args) {
                return "ok";
            }
        };
        Map<String, Object> schema = stub.getInputSchema();
        assertEquals("object", schema.get("type"));
        Map<?, ?> props = (Map<?, ?>) schema.get("properties");
        assertEquals("string", ((Map<?, ?>) props.get("name")).get("type"));
        assertEquals("integer", ((Map<?, ?>) props.get("tick")).get("type"));
    }
}
