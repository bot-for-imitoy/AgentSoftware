package com.agent.software.tool.note;

import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.infra.json.JsonCodec;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.NoteId;
import com.agent.software.kernel.Ids.RoleId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonNoteBook} 单元测试（迁移自 master {@code core/NoteStoreTest}）。
 *
 * <p>行为基线：标题即业务主键、总结前缀约定、{@code latestSummary} 严格早于 beforeDay、
 * 损坏文件降级为空表、写入走 .tmp + move 原子替换（不留残留）。
 */
class JsonNoteBookTest {

    @TempDir
    Path tmp;

    private static final RoleId CEO = new RoleId("CEO");

    private final JsonCodec json = new JacksonJsonCodec();
    private AppPaths paths;

    @BeforeEach
    void setUp() {
        // 所有文件操作只落在 @TempDir 下，绝不碰真实 ~/.local/share 或仓库 data/
        paths = AppPaths.resolve(new AppConfig.Storage(tmp.resolve("data").toString()));
    }

    private JsonNoteBook book() {
        return new JsonNoteBook(paths, json);
    }

    private Path noteFile(RoleId owner) {
        return paths.dataFile("notes", owner.value() + ".json");
    }

    private static Note note(String title, String body) {
        return new Note(new NoteId("n-" + title.hashCode()), CEO, title, body, null, null, Instant.now());
    }

    // ── 写 / 读 / 列表：标题即业务主键 ──────────────────────────

    @Test
    void 写入读取列表且标题为主键() {
        JsonNoteBook book = book();
        book.write(note("Weekly report", "This week's summary"));
        book.write(note("Plan", "Next week's plan"));

        List<String> titles = book.list(CEO).stream().map(Note::title).toList();
        assertEquals(2, titles.size());
        assertTrue(titles.containsAll(List.of("Weekly report", "Plan")));

        assertEquals("This week's summary", book.read(CEO, "Weekly report").orElseThrow().body());

        // 同标题覆盖：列表仍是 2 条，正文被整篇替换，id 保留（标题是主键）
        NoteId firstId = book.read(CEO, "Weekly report").orElseThrow().id();
        book.write(note("Weekly report", "Updated content"));
        assertEquals(2, book.list(CEO).size());
        Note reread = book.read(CEO, "Weekly report").orElseThrow();
        assertEquals("Updated content", reread.body());
        assertEquals(firstId, reread.id(), "同标题覆盖应保留原 id");

        assertTrue(book.read(CEO, "nonexistent").isEmpty());
    }

    @Test
    void edit更新正文且不新建条目() {
        JsonNoteBook book = book();
        book.write(note("Memo", "v1"));
        NoteId id = book.read(CEO, "Memo").orElseThrow().id();

        book.edit(new Note(id, CEO, "Memo", "v2", null, null, Instant.now()));
        assertEquals("v2", book.read(CEO, "Memo").orElseThrow().body());
        assertEquals(1, book.list(CEO).size());
    }

    @Test
    void delete删除并返回是否命中() {
        JsonNoteBook book = book();
        book.write(note("Memo", "Content"));
        Path file = noteFile(CEO);
        assertTrue(Files.exists(file));

        assertTrue(book.delete(CEO, "Memo"));
        assertFalse(Files.exists(file), "delete 必须真的删掉磁盘上的条目");
        assertFalse(book.delete(CEO, "Memo"), "重复删除返回 false");
        assertTrue(book.list(CEO).isEmpty());
    }

    // ── 总结：前缀约定 + 按天数值排序 ──────────────────────────

    @Test
    void 总结按天数值排序且严格早于beforeDay() {
        JsonNoteBook book = book();
        book.saveSummary(CEO, 9, "Day 9 summary");
        book.saveSummary(CEO, 10, "Day 10 summary");

        assertEquals("Day 9 summary", book.summary(CEO, 9).orElseThrow());
        assertEquals("Day 10 summary", book.summary(CEO, 10).orElseThrow());
        assertTrue(book.summary(CEO, 11).isEmpty());

        // 严格早于：beforeDay=11 取第 10 天；beforeDay=10 取第 9 天；beforeDay=9 没有更早的
        assertEquals("Day 10 summary", book.latestSummary(CEO, 11).orElseThrow());
        assertEquals("Day 9 summary", book.latestSummary(CEO, 10).orElseThrow());
        assertTrue(book.latestSummary(CEO, 9).isEmpty());
    }

    @Test
    void 总结走标题前缀约定且不与普通笔记撞名() {
        JsonNoteBook book = book();
        book.saveSummary(CEO, 3, "第三天的总结");

        String expectedTitle = Note.SUMMARY_TITLE_PREFIX + 3;
        Note stored = book.read(CEO, expectedTitle).orElseThrow();
        assertTrue(stored.isSummary());
        assertEquals(Note.SUMMARY_TITLE_PREFIX, "__summary_day_");

        // 普通笔记不会被 latestSummary 当成总结
        book.write(note("随便写成 __summary_day_ 的普通标题", "不是总结"));
        assertEquals("第三天的总结", book.latestSummary(CEO, 10).orElseThrow());
    }

    @Test
    void 没有总结时latestSummary为空() {
        JsonNoteBook book = book();
        book.write(note("普通笔记", "正文"));
        assertTrue(book.latestSummary(CEO, 5).isEmpty());
    }

    // ── 降级与原子写 ────────────────────────────────────────────

    @Test
    void 损坏文件降级为空表且可覆写() throws IOException {
        JsonNoteBook book = book();
        Path file = noteFile(CEO);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "这不是 JSON {{{", StandardCharsets.UTF_8);

        assertTrue(book.list(CEO).isEmpty(), "损坏文件应按空表处理");
        assertTrue(book.read(CEO, "任意").isEmpty());

        // 覆写后恢复正常
        book.write(note("Recovered", "ok"));
        assertEquals("ok", book.read(CEO, "Recovered").orElseThrow().body());
    }

    @Test
    void 原子写不残留tmp文件() throws IOException {
        JsonNoteBook book = book();
        book.write(note("Memo", "Content"));
        book.saveSummary(CEO, 1, "总结");

        try (Stream<Path> files = Files.list(noteFile(CEO).getParent())) {
            List<String> leftovers = files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".tmp")).toList();
            assertTrue(leftovers.isEmpty(), "原子写不应残留 .tmp: " + leftovers);
        }
    }

    @Test
    void 空参数被拒绝() {
        JsonNoteBook book = book();
        assertThrows(DomainError.class, () -> book.write(null));
        assertThrows(DomainError.class, () -> book.write(note("   ", "body")));
        assertThrows(DomainError.class, () -> book.list(null));
        assertThrows(DomainError.class, () -> book.latestSummary(null, 3));
    }

    @Test
    void 跨实例持久化可见() {
        book().write(note("Persisted", "value"));
        Optional<Note> reread = book().read(CEO, "Persisted");
        assertTrue(reread.isPresent());
        assertEquals("value", reread.get().body());
    }
}
