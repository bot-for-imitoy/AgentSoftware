package com.agent.software.store;

import com.agent.software.tools.Tool;
import com.agent.software.tools.toolkits.todo.Todo;
import com.agent.software.utils.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** todo 组 + 事项：增删改查、分组隔离、切换基线、实时落盘、旧格式迁移、工具层。 */
class TodoStoreAndToolsTest {

    @Test
    void crudInTheDefaultGroupAndEveryChangeIsSaved(@TempDir Path dir) {
        TodoStore store = new TodoStore(dir, "CEO");
        assertEquals(List.of("default"), store.groups());
        assertEquals("default", store.currentGroup());

        TodoStore.Item a = store.add("write the SRS", "cover V1-V5", "");
        TodoStore.Item b = store.add("review the design", "", "");
        assertNotNull(a.id);
        assertEquals(8, a.id.length());
        assertEquals("pending", a.status);
        assertEquals(2, store.count("default"));

        // 改状态后立刻落盘：换一个 store 实例读同一个文件也能看到
        assertNotNull(store.update(b.id, "completed", null, null));
        TodoStore reRead = new TodoStore(dir, "CEO");
        assertEquals(2, reRead.count("default"));
        assertEquals("completed", reRead.items("default").get(1).status);
        assertEquals("write the SRS", reRead.items("default").get(0).title);
        assertTrue(Files.exists(dir.resolve("CEO.json")));

        // 前缀也能定位；打完分之后删除
        assertNotNull(store.find("default", a.id.substring(0, 4)));
        assertTrue(store.delete(a.id.substring(0, 4)));
        assertEquals(1, new TodoStore(dir, "CEO").count("default"));
        assertFalse(store.delete("deadbeef"));
    }

    @Test
    void groupsAreIsolatedAndSwitchingMovesTheBaseline(@TempDir Path dir) {
        TodoStore store = new TodoStore(dir, "CEO");
        store.add("baseline item", "", "");
        assertTrue(store.switchGroup("release"), "不存在的组应被创建");
        assertEquals("release", store.currentGroup());
        assertEquals(0, store.count(""));
        store.add("release item", "", "");
        assertEquals(1, store.count(""));
        assertEquals(1, store.count("default"));

        // 显式指定组时不看基线
        store.add("back to default", "", "default");
        assertEquals(2, store.count("default"));

        assertFalse(store.switchGroup("default"), "已有组不算新建");
        assertEquals(List.of("default", "release"), store.groups());
        assertEquals("baseline item", store.items("").get(0).title);

        // 换实例复核：组结构、基线、事项都存下来了
        TodoStore reRead = new TodoStore(dir, "CEO");
        assertEquals("default", reRead.currentGroup());
        assertEquals(List.of("default", "release"), reRead.groups());
        assertEquals(2, reRead.count("default"));
        assertEquals(1, reRead.count("release"));
    }

    @Test
    void legacyFlatFileIsMigratedIntoTheDefaultGroup(@TempDir Path dir) {
        Path legacy = dir.resolve("CEO.json");
        Json.writeFile(legacy, List.of(
                Map.of("id", "aaaaaaaa", "title", "old task", "detail", "d",
                        "status", "completed", "created_at", 1L, "updated_at", 2L)));

        TodoStore store = new TodoStore(dir, "CEO");
        assertEquals(List.of("default"), store.groups());
        assertEquals(1, store.count("default"));
        TodoStore.Item it = store.items("default").get(0);
        assertEquals("old task", it.title);
        assertEquals("completed", it.status);
    }

    @Test
    void toolkitExposesTheTodoToolsAndTheyWork(@TempDir Path dir) {
        Todo todo = new Todo(new TodoStore(dir, "CEO"));
        List<String> names = todo.getTools().stream().map(Tool::getToolName).sorted().toList();
        assertEquals(List.of("todo_add", "todo_delete", "todo_group_list", "todo_group_switch",
                "todo_list", "todo_update"), names);

        String added = todo.trigger("todo_add", Map.of("title", "ship the demo"));
        assertTrue(added.contains("todo_add: ["), added);
        String id = added.substring(added.indexOf('[') + 1, added.indexOf(']'));

        assertTrue(todo.trigger("todo_group_list", Map.of()).contains("baseline = default"));
        assertTrue(todo.trigger("todo_list", Map.of()).contains("ship the demo"));
        assertTrue(todo.trigger("todo_update", Map.of("todo_id", id, "status", "completed"))
                .contains("completed"));
        assertTrue(todo.trigger("todo_list", Map.of("status", "completed")).contains("ship the demo"));
        assertTrue(todo.trigger("todo_list", Map.of("status", "pending")).contains("(empty)"));

        // 切到另一个组：基线换了，旧组的事项还在
        String switched = todo.trigger("todo_group_switch", Map.of("name", "release"));
        assertTrue(switched.contains("baseline is now 'release'"), switched);
        assertTrue(todo.trigger("todo_list", Map.of()).contains("(empty)"));
        todo.trigger("todo_add", Map.of("title", "release checklist"));
        String groups = todo.trigger("todo_group_list", Map.of());
        assertTrue(groups.contains("default (1 item(s), 1 completed)"), groups);
        assertTrue(groups.contains("release (1 item(s), 0 completed)"), groups);

        // 删掉当前组里的事项
        String added2 = todo.trigger("todo_add", Map.of("title", "temp"));
        String id2 = added2.substring(added2.indexOf('[') + 1, added2.indexOf(']'));
        assertTrue(todo.trigger("todo_delete", Map.of("todo_id", id2)).contains("removed"));
    }

    @Test
    void toolsValidateTheirArguments(@TempDir Path dir) {
        Todo todo = new Todo(new TodoStore(dir, "CEO"));
        assertTrue(todo.trigger("todo_add", Map.of()).contains("needs a title"));
        assertTrue(todo.trigger("todo_update", Map.of()).contains("needs a todo_id"));
        assertTrue(todo.trigger("todo_update", Map.of("todo_id", "abcd1234")).contains("nothing to change"));
        assertTrue(todo.trigger("todo_update", Map.of("todo_id", "abcd1234", "status", "completed"))
                .contains("no unique item"));
        assertTrue(todo.trigger("todo_delete", Map.of()).contains("needs a todo_id"));
        assertTrue(todo.trigger("todo_delete", Map.of("todo_id", "abcd1234")).contains("no unique item"));
        assertTrue(todo.trigger("todo_group_switch", Map.of()).contains("needs a group name"));
        assertNull(new TodoStore(dir, "CEO").find("default", "nope"));
    }
}
