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

/** todo：**一层平铺**的清单（没有"组"），增删改查 + 实时落盘 + 旧格式兼容。 */
class TodoStoreAndToolsTest {

    @Test
    void crudIsFlatAndEveryChangeIsSaved(@TempDir Path dir) {
        TodoStore store = new TodoStore(dir, "CEO");
        assertEquals(0, store.count());

        TodoStore.Item a = store.add("write the SRS", "cover V1-V5");
        TodoStore.Item b = store.add("review the design", "");
        assertNotNull(a.id);
        assertEquals(8, a.id.length());
        assertEquals("pending", a.status);
        assertEquals(2, store.count());
        assertTrue(Files.exists(dir.resolve("CEO.json")));

        // 改状态后立刻落盘：换一个 store 实例读同一个文件也能看到
        assertNotNull(store.update(b.id, "completed", null, null));
        TodoStore reRead = new TodoStore(dir, "CEO");
        assertEquals(2, reRead.count());
        assertEquals("completed", reRead.items().get(1).status);
        assertEquals("write the SRS", reRead.items().get(0).title);

        // 前缀也能定位
        assertNotNull(store.find(a.id.substring(0, 4)));
        assertTrue(store.delete(a.id.substring(0, 4)));
        assertEquals(1, new TodoStore(dir, "CEO").count());
        assertFalse(store.delete("deadbeef"));
        assertNull(store.find("nope"));
    }

    @Test
    void legacyFlatArrayLoadsAndGroupedFileIsFlattened(@TempDir Path dir) {
        // master 时代的裸数组
        Json.writeFile(dir.resolve("CEO.json"), List.of(
                Map.of("id", "aaaaaaaa", "title", "old task", "detail", "d",
                        "status", "completed", "created_at", 1L, "updated_at", 2L)));
        TodoStore flat = new TodoStore(dir, "CEO");
        assertEquals(1, flat.count());
        assertEquals("old task", flat.items().get(0).title);
        assertEquals("completed", flat.items().get(0).status);

        // 中途出现过的 {"groups": {...}} 结构 → 拍平合并
        Json.writeFile(dir.resolve("COO.json"), Map.of(
                "current_group", "release",
                "groups", Map.of(
                        "default", List.of(Map.of("id", "bbbbbbbb", "title", "one", "status", "pending")),
                        "release", List.of(Map.of("id", "cccccccc", "title", "two", "status", "completed")))));
        TodoStore flattened = new TodoStore(dir, "COO");
        assertEquals(2, flattened.count());
        assertTrue(flattened.items().stream().anyMatch(i -> i.title.equals("one")));
        assertTrue(flattened.items().stream().anyMatch(i -> i.title.equals("two")));
    }

    @Test
    void toolkitHasOnlyTheFourBasicTools(@TempDir Path dir) {
        Todo todo = new Todo(new TodoStore(dir, "CEO"));
        List<String> names = todo.getTools().stream().map(Tool::getToolName).sorted().toList();
        assertEquals(List.of("todo_add", "todo_delete", "todo_list", "todo_update"), names);

        String added = todo.trigger("todo_add", Map.of("title", "ship the demo"));
        assertTrue(added.contains("todo_add: ["), added);
        String id = added.substring(added.indexOf('[') + 1, added.indexOf(']'));

        assertTrue(todo.trigger("todo_list", Map.of()).contains("ship the demo"));
        assertTrue(todo.trigger("todo_update", Map.of("todo_id", id, "status", "completed"))
                .contains("completed"));
        assertTrue(todo.trigger("todo_list", Map.of("status", "completed")).contains("ship the demo"));
        assertTrue(todo.trigger("todo_list", Map.of("status", "pending")).contains("(empty)"));
        assertTrue(todo.trigger("todo_delete", Map.of("todo_id", id)).contains("removed"));
        assertTrue(todo.trigger("todo_list", Map.of()).contains("0 item(s)"));
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
        // 没有任何组相关的参数/工具
        assertFalse(todo.trigger("todo_list", Map.of()).contains("group"));
        assertNull(new TodoStore(dir, "CEO").find("nope"));
    }
}
