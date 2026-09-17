package com.agent.software.company.store;

import com.agent.software.agent.AgentState;
import com.agent.software.agent.RoleSnapshot;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.agent.task.Task;
import com.agent.software.bootstrap.CompanyBuilder;
import com.agent.software.company.Company;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.kernel.DayTick;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.llm.LlmClient;
import com.agent.software.llm.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonSnapshotStore} 快照落盘/读档测试。
 *
 * <p>迁移自 master 的 {@code StateStoreTest}。master 的 {@code StateStore} 既组装
 * {@code AgentSystem} 的运行时对象又落盘；新架构里 {@code Company.save()} 组装 typed 的
 * {@link CompanySnapshot}，存储层只负责"原子写 + 容错读"。
 *
 * <p>版本策略的位置：存储层只解析磁盘形状；版本号比对在读档入口 {@code Company.restore()}
 * （对齐 master {@code StateStore.restore} 的版本判断）。因此"版本不符降级为无档"在这里
 * 通过 {@code Company.restore()} 验证，而不是把版本策略塞进存储层。
 */
class JsonSnapshotStoreTest {

    @TempDir
    Path dir;

    private final JacksonJsonCodec json = new JacksonJsonCodec();

    private AppPaths paths() {
        return AppPaths.resolve(new AppConfig.Storage(dir.toString()));
    }

    private JsonSnapshotStore store() {
        return new JsonSnapshotStore(paths(), json);
    }

    // ── 测试夹具 ────────────────────────────────────────────────

    private static RoleSpec spec() {
        return RoleSpec.builder()
                .id(new RoleId("ceo")).name("Lin").username("lin")
                .title("CEO").skills(List.of("Strategy"))
                .group("管理层").email("lin@agentsoftware.local")
                .toolkits(Set.of())
                .build();
    }

    private static CompanySnapshot snapshot(int version) {
        Task.TaskRecord pending = new Task.TaskRecord("t-1", 6, "[email/NEW_MAIL] 客户催单",
                "email/NEW_MAIL", Map.of("title", "客户催单"), "PENDING", "", 0,
                1_700_000_000.0, "ceo");
        Task.TaskRecord done = new Task.TaskRecord("t-0", 3, "[time/SHIFT_START] 今日计划",
                "time/SHIFT_START", Map.of(), "DONE", "已完成", 42,
                1_699_999_000.0, "ceo");
        List<Message> conversation = List.of(
                Message.user("收集需求"),
                Message.assistant("需求已记录", List.of()));
        RoleSnapshot role = new RoleSnapshot(spec(), AgentState.ON_DUTY_IDLE,
                List.of(pending), List.of(done), 1, conversation);
        return new CompanySnapshot(version, Instant.parse("2026-09-16T00:00:00Z"),
                new DayTick(1, 30), LocalDate.of(2026, 9, 16), List.of(role));
    }

    /** 读档入口：与存储层共用同一份路径配置。 */
    private Company company() {
        AppConfig config = new AppConfig(
                new AppConfig.Llm("openai", "test", "k", "http://localhost:1",
                        new AppConfig.Llm.Retry(1, 0.01, 5)),
                new AppConfig.Schedule(1.0, 8, 18, 0L, 1.0, 600_000L),
                new AppConfig.Storage(dir.toString()),
                new AppConfig.Web("127.0.0.1", 0, 60_000L),
                new AppConfig.Mail("agentsoftware.local",
                        new AppConfig.Mail.Smtp("", 587, "", "", "", true)),
                new AppConfig.Toolkits(Set.of()));
        return new CompanyBuilder(config, AppPaths.resolve(config.storage()), json)
                .withLlm(new NoopLlm())
                .build();
    }

    private static final class NoopLlm implements LlmClient {
        @Override
        public ChatReply chat(ChatRequest request) {
            return new ChatReply("", null, 0);
        }

        @Override
        public ToolReply chatWithTools(ToolChatRequest request) {
            return new ToolReply("", null, List.of(), 0);
        }

        @Override
        public ChatReply summarize(String text, double temperature, int maxTokens) {
            return new ChatReply("", null, 0);
        }
    }

    private void write(String content) {
        Path file = paths().ensure(store().file());
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("写测试档失败", e);
        }
    }

    // ── 往返 ────────────────────────────────────────────────────

    @Test
    void 保存后可原样读回() {
        JsonSnapshotStore store = store();
        CompanySnapshot original = snapshot(Company.SNAPSHOT_VERSION);
        store.save(original);

        Optional<CompanySnapshot> loaded = store.load();
        assertTrue(loaded.isPresent());
        assertEquals(original, loaded.get(), "save → load 必须是无损往返");
    }

    @Test
    void 版本一致的档可以恢复角色() {
        store().save(snapshot(Company.SNAPSHOT_VERSION));
        assertEquals(1, company().restore(), "版本一致时应从档里恢复 1 个角色");
    }

    // ── 原子写 ──────────────────────────────────────────────────

    @Test
    void 原子写不残留临时文件() throws Exception {
        JsonSnapshotStore store = store();
        store.save(snapshot(Company.SNAPSHOT_VERSION));

        Path file = store.file();
        assertTrue(Files.exists(file), "state.json 应存在");
        try (var files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).sorted().toList();
            assertTrue(names.stream().noneMatch(n -> n.contains(".tmp")), "不得残留 .tmp：" + names);
            assertEquals(List.of(JsonSnapshotStore.FILE_NAME), names);
        }
        assertEquals(Company.SNAPSHOT_VERSION,
                json.read(Files.readString(file, StandardCharsets.UTF_8), CompanySnapshot.class).version(),
                "落盘内容必须是完整可解析的 JSON");
    }

    @Test
    void 覆盖写后保留最新内容且只有一个文件() throws Exception {
        JsonSnapshotStore store = store();
        store.save(snapshot(Company.SNAPSHOT_VERSION));

        CompanySnapshot second = new CompanySnapshot(Company.SNAPSHOT_VERSION,
                Instant.parse("2026-09-17T00:00:00Z"), new DayTick(2, 0),
                LocalDate.of(2026, 9, 17), List.of());
        store.save(second);

        assertEquals(second, store.load().orElseThrow());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "覆盖写后目录里只应有一个 state.json");
        }
    }

    @Test
    void 保存null不会创建文件() {
        JsonSnapshotStore store = store();
        store.save(null);
        assertTrue(Files.notExists(store.file()));
    }

    // ── 容错读档：损坏 / 字段不符 / 缺失 ────────────────────────

    @Test
    void 损坏档降级为无档() {
        write("{ this is not json");
        assertTrue(store().load().isEmpty());
    }

    @Test
    void 字段类型不符的档降级为无档() {
        // roles 期望是数组，这里是对象 → 解析失败 → 视为无档
        write("{\"version\":1,\"roles\":{\"CEO\":{}}}");
        assertTrue(store().load().isEmpty());
    }

    @Test
    void 空文件与不存在的档都视为无档() {
        JsonSnapshotStore store = store();
        assertTrue(store.load().isEmpty(), "文件不存在 → 无档");

        write("   ");
        assertTrue(store.load().isEmpty(), "空文件 → 无档");
    }

    // ── 版本不符：由读档入口降级为无档 ──────────────────────────

    @Test
    void 版本不符的档由读档入口拒绝() {
        JsonSnapshotStore store = store();
        store.save(snapshot(999));

        // 存储层只负责解析：磁盘形状合法，load() 能读回原始版本号
        assertEquals(999, store.load().orElseThrow().version());

        // 版本策略在 Company.restore：不一致时跳过读档（降级为"无档，从第 1 天开始"）
        assertEquals(0, company().restore());
    }
}
