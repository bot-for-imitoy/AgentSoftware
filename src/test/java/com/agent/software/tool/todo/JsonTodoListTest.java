package com.agent.software.tool.todo;

import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.infra.json.JsonCodec;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TodoId;
import com.agent.software.tool.todo.Todo.TodoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonTodoList} 存储层单元测试（迁移自 master {@code core/TodoStoreTest} 部分）。
 *
 * <p>注意：master 的 {@code TodoStore.update(String status)} 用字符串并抛
 * {@code IllegalArgumentException}；新架构成类型化为 {@link TodoStatus}，非法状态在
 * 工具层（{@code TodoToolkit}）拦截并返回 {@code ToolResult.error}——该行为在
 * {@code ToolkitsTest} 里覆盖。
 */
class JsonTodoListTest {

    @TempDir
    Path tmp;

    private static final RoleId OWNER = new RoleId("tester_1");

    private final JsonCodec json = new JacksonJsonCodec();
    private AppPaths paths;

    @BeforeEach
    void setUp() {
        paths = AppPaths.resolve(new AppConfig.Storage(tmp.resolve("data").toString()));
    }

    private JsonTodoList list() {
        return new JsonTodoList(paths, json);
    }

    private Path todoFile() {
        return paths.dataFile("todos", OWNER.value() + ".json");
    }

    @Test
    void 增删改查与状态过滤() {
        JsonTodoList todos = list();
        Todo item = todos.add(OWNER, "Write weekly report", "This week's summary");
        assertEquals(TodoStatus.PENDING, item.status());
        assertEquals("Write weekly report", item.title());
        assertEquals("This week's summary", item.detail());

        // 重新加载（持久化生效）
        JsonTodoList reloaded = list();
        assertEquals(1, reloaded.list(OWNER, null).size());

        // 状态迁移
        Optional<Todo> inProgress = todos.update(OWNER, item.id(), TodoStatus.IN_PROGRESS);
        assertEquals(TodoStatus.IN_PROGRESS, inProgress.orElseThrow().status());
        assertEquals(TodoStatus.COMPLETED,
                todos.update(OWNER, item.id(), TodoStatus.COMPLETED).orElseThrow().status());

        // 过滤
        assertTrue(todos.list(OWNER, TodoStatus.PENDING).isEmpty());
        assertEquals(1, todos.list(OWNER, TodoStatus.COMPLETED).size());

        // 删除
        assertTrue(todos.delete(OWNER, item.id()));
        assertFalse(todos.delete(OWNER, item.id()));
        assertTrue(todos.list(OWNER, null).isEmpty());
    }

    @Test
    void 未知id返回空或false() {
        JsonTodoList todos = list();
        TodoId ghost = new TodoId("ghost");
        assertTrue(todos.update(OWNER, ghost, TodoStatus.COMPLETED).isEmpty());
        assertFalse(todos.delete(OWNER, ghost));
        assertTrue(todos.update(OWNER, null, TodoStatus.COMPLETED).isEmpty());
        assertFalse(todos.delete(OWNER, null));
    }

    @Test
    void 空标题被拒绝() {
        JsonTodoList todos = list();
        assertThrows(DomainError.class, () -> todos.add(OWNER, "   ", "detail"));
        assertThrows(DomainError.class, () -> todos.add(null, "x", "y"));
    }

    @Test
    void 列表顺序即创建顺序() {
        JsonTodoList todos = list();
        todos.add(OWNER, "第一条", "");
        todos.add(OWNER, "第二条", "");
        assertEquals(List.of("第一条", "第二条"),
                todos.list(OWNER, null).stream().map(Todo::title).toList());
    }

    @Test
    void 损坏文件降级为空表且可覆写() throws IOException {
        JsonTodoList todos = list();
        Path file = todoFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ 坏掉的 JSON", StandardCharsets.UTF_8);

        assertTrue(todos.list(OWNER, null).isEmpty());
        Todo added = todos.add(OWNER, "恢复后的待办", "");
        assertEquals(1, list().list(OWNER, null).size());
        assertEquals(added.id(), list().list(OWNER, null).get(0).id());
    }

    @Test
    void 原子写不残留tmp文件() throws IOException {
        JsonTodoList todos = list();
        todos.add(OWNER, "x", "");
        try (Stream<Path> files = Files.list(todoFile().getParent())) {
            List<String> leftovers = files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".tmp")).toList();
            assertTrue(leftovers.isEmpty(), "原子写不应残留 .tmp: " + leftovers);
        }
    }
}
