package com.agent.software.bootstrap;

import com.agent.software.agent.Agent;
import com.agent.software.agent.AgentFactory;
import com.agent.software.agent.AgentMailbox;
import com.agent.software.agent.AgentStateMachine;
import com.agent.software.agent.LifecycleGate;
import com.agent.software.agent.Staffing;
import com.agent.software.agent.Team;
import com.agent.software.agent.ToolboxFactory;
import com.agent.software.agent.WaitCoordinator;
import com.agent.software.agent.dialog.ConversationMemory;
import com.agent.software.agent.dialog.ConversationPolicy;
import com.agent.software.agent.dialog.SystemPrompt;
import com.agent.software.agent.dispatch.DefaultDeliveryPolicy;
import com.agent.software.agent.dispatch.EventRouter;
import com.agent.software.agent.dispatch.KeywordSaliencePolicy;
import com.agent.software.agent.dispatch.TaskFactory;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.agent.task.ToolLoop;
import com.agent.software.agent.task.ToolLoopPolicy;
import com.agent.software.company.Company;
import com.agent.software.company.ShiftDirector;
import com.agent.software.company.store.JsonSnapshotStore;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JsonCodec;
import com.agent.software.kernel.Payload;
import com.agent.software.llm.LlmClient;
import com.agent.software.llm.EmbeddingClient;
import com.agent.software.llm.EmbeddingModel;
import com.agent.software.llm.OpenAiClient;
import com.agent.software.llm.ProviderCatalog;
import com.agent.software.llm.ProviderResolver;
import com.agent.software.llm.RetryArbiter;
import com.agent.software.sim.clock.ClockDriver;
import com.agent.software.sim.clock.ClockPolicy;
import com.agent.software.sim.clock.DefaultClockPolicy;
import com.agent.software.sim.clock.ScheduleTable;
import com.agent.software.sim.clock.ShiftCalendar;
import com.agent.software.sim.clock.SimClock;
import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.EventKind;
import com.agent.software.sim.event.Priority;
import com.agent.software.tool.client.ClientChannel;
import com.agent.software.tool.client.ClientToolkit;
import com.agent.software.tool.client.ConsoleClientChannel;
import com.agent.software.tool.computer.HermesToolkit;
import com.agent.software.tool.computer.PcToolkit;
import com.agent.software.tool.computer.Shell;
import com.agent.software.tool.computer.ShellRegistry;
import com.agent.software.tool.hr.HiringService;
import com.agent.software.tool.hr.HrToolkit;
import com.agent.software.tool.mail.EmailToolkit;
import com.agent.software.tool.mail.FileMailbox;
import com.agent.software.tool.mcp.McpToolkit;
import com.agent.software.tool.mcp.StdioMcpBridge;
import com.agent.software.tool.note.JsonNoteBook;
import com.agent.software.tool.note.MemoryToolkit;
import com.agent.software.tool.note.NoteToolkit;
import com.agent.software.tool.skill.JsonSkillLibrary;
import com.agent.software.tool.skill.SkillToolkit;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolbox;
import com.agent.software.tool.talk.TalkService;
import com.agent.software.tool.talk.TalkToolkit;
import com.agent.software.tool.task.TaskViewToolkit;
import com.agent.software.tool.time.TimeToolkit;
import com.agent.software.tool.todo.JsonTodoList;
import com.agent.software.tool.todo.TodoToolkit;
import com.agent.software.transcript.ChatFeed;
import com.agent.software.transcript.Transcript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.Duration;
import java.util.List;

/**
 * 组合根：唯一允许"认识所有东西"的地方。
 *
 * <p>目标装配顺序（无 setter、无 holder、无环）：
 * <pre>
 * AppPaths / JsonCodec / Transcript.Feed
 *   → Team（无依赖，先建）
 *   → TalkService(Team) / 各 adapter
 *   → ToolkitCatalog（注入各工具包的共享能力与该角色的每角色能力）
 *   → AgentFactory(Team 只读视图 + ShellRegistry + ToolboxFactory + Llm + gate)
 *   → Staffing(Team, AgentFactory)
 *   → HiringService / EventRouter / TaskFactory / ScheduleTable / ClockDriver / ShiftDirector
 *   → Company(...)
 * </pre>
 * 对比 master：{@code AgentSystem} 既当组合根又当运行时，还要用 setter 把
 * clock/pool/dispatcher/mail 互相回填。
 */
public final class CompanyBuilder {

    private static final Logger logger = LoggerFactory.getLogger(CompanyBuilder.class);

    /** 时钟线程在"有人忙"时的轮询间隔（对齐 master {@code BUSY_POLL_MILLIS}）。 */
    private static final long BUSY_POLL_MILLIS = 250L;

    /** 时钟线程在"全员空闲"时的轮询间隔。 */
    private static final long IDLE_POLL_MILLIS = 1_000L;

    /** 容器共享网络名（podman 形态的个人电脑用它组网）——直接引用唯一的默认值，避免两处各写一份。 */
    private static final String CONTAINER_NETWORK = ShellRegistry.DEFAULT_NETWORK;

    private final AppConfig config;
    private final AppPaths paths;
    private final JsonCodec json;

    private LlmClient llmOverride;
    private ClientChannel clientChannel;
    private Transcript.Feed transcript;

    public CompanyBuilder(AppConfig config, AppPaths paths, JsonCodec json) {
        this.config = config;
        this.paths = paths;
        this.json = json;
    }

    /** 用外部 LLM 覆盖配置（测试 / 嵌入）。 */
    public CompanyBuilder withLlm(LlmClient llm) {
        this.llmOverride = llm;
        return this;
    }

    /** 指定客户输入通道（控制台或 Web）。 */
    public CompanyBuilder withClientChannel(ClientChannel channel) {
        this.clientChannel = channel;
        return this;
    }

    /** 指定轨迹实现（默认内存 ChatFeed；测试可换成 fake）。 */
    public CompanyBuilder withTranscript(Transcript.Feed feed) {
        this.transcript = feed;
        return this;
    }

    /** 组装出可运行的 {@link Company}（含 Web 输入通道所需的 feed）。 */
    public Company build() {
        // 1. 基础设施：日历、时钟、暂停门、轨迹
        ShiftCalendar calendar = ShiftCalendar.of(config.schedule().secondsPerTick(),
                config.schedule().shiftStartHour(), config.schedule().shiftEndHour());
        SimClock clock = new SimClock(calendar, LocalDate.now());
        LifecycleGate gate = new LifecycleGate();
        Transcript.Feed feed = transcript != null ? transcript : new ChatFeed();

        // 2. LLM 适配器：解析 endpoint → 共享限流仲裁器 → 客户端
        LlmClient llm = buildLlm(gate);
        EmbeddingModel embeddings = buildEmbeddings();

        // 3. 花名册与共享能力（这些都是"进程一份"的适配器）
        Team team = new Team(clock);
        JsonNoteBook notes = new JsonNoteBook(paths, json);
        JsonTodoList todos = new JsonTodoList(paths, json);
        JsonSkillLibrary skills = new JsonSkillLibrary(paths);
        FileMailbox mail = new FileMailbox(config.mail(), paths);
        StdioMcpBridge mcp = new StdioMcpBridge(paths);
        ShellRegistry shells = new ShellRegistry(paths, CONTAINER_NETWORK);
        // 日程表与时钟必须共用同一份日历（否则 tick ↔ 触发时刻的换算会不一致）
        ScheduleTable schedule = new ScheduleTable(calendar);
        TalkService talk = new TalkService(team, feed);
        ClientChannel client = clientChannel != null ? clientChannel : new ConsoleClientChannel();

        // 4. 事件路由（唯一投递判断点）
        TaskFactory taskFactory = new TaskFactory(schedule);
        EventRouter router = new EventRouter(team, new DefaultDeliveryPolicy(
                KeywordSaliencePolicy.defaults()), taskFactory, feed);

        // 5. 邮件落信 → NEW_MAIL 定向事件（邮箱地址 → 角色）
        wireMailNotifications(mail, team, router);

        // 6. 工具目录：每个 id 一个工厂，能力在构造期注入
        ToolkitCatalog catalog = new ToolkitCatalog()
                .register("memory", deps -> new MemoryToolkit(notes, clock, deps.control()))
                .register("note", deps -> new NoteToolkit(notes, schedule))
                .register("time", deps -> new TimeToolkit(clock))
                .register("todo", deps -> new TodoToolkit(todos))
                .register("task_view", deps -> new TaskViewToolkit(deps.tasks()))
                .register("pc", deps -> new PcToolkit(deps.shell()))
                .register("hermes", deps -> new HermesToolkit(deps.shell()))
                .register("mcp_manager", deps -> new McpToolkit(mcp))
                .register("skill", deps -> new SkillToolkit(skills))
                .register("email", deps -> new EmailToolkit(mail, team))
                .register("talk", deps -> new TalkToolkit(talk, team, feed))
                .register("client", deps -> new ClientToolkit(client, team, feed));

        // 7. 角色的装配工厂：每角色一套队列/状态机/等待/对话/工具箱
        AgentFactory agentFactory = new AgentFactory() {
            @Override
            public Agent create(RoleSpec spec) {
                AgentStateMachine stateMachine = new AgentStateMachine();
                AgentMailbox mailbox = new AgentMailbox();
                WaitCoordinator waits = new WaitCoordinator(stateMachine);
                ConversationPolicy defaults = ConversationPolicy.defaults();
                ConversationPolicy conversationPolicy = new ConversationPolicy(
                        defaults.maxHistoryChars(), defaults.maxSummaryChars(), defaults.toolRecapLimit(),
                        config.llm().maxContextMessages());
                ConversationMemory memory = new ConversationMemory(spec.id(), conversationPolicy, embeddings);
                SystemPrompt prompts = new SystemPrompt(clock, notes);
                Shell shell = shells.create(spec);

                // 工具箱要等 Agent 存在才能装配（工具需要 AgentTasks/AgentControl），
                // 但 ToolLoop 在构造期就要一个 Toolbox —— 用一个延迟代理接上这条缝。
                DeferredToolbox deferred = new DeferredToolbox();
                ToolLoop toolLoop = new ToolLoop(llm, deferred, feed, ToolLoopPolicy.defaults());
                ToolboxFactory toolboxFactory = (s, tasks, control) -> {
                    Toolbox box = catalog.build(s, shell, tasks, control);
                    deferred.attach(box);
                    return box;
                };
                return new Agent(spec, shell, mailbox, stateMachine, waits, toolboxFactory,
                        memory, toolLoop, prompts, gate);
            }
        };

        // 8. 人员与招聘（Staffing 先于 HiringService；hr 工具包最后注册也不影响，
        //    因为任何 Agent.start() 都发生在 Company.start() 时）
        Staffing staffing = new Staffing(team, agentFactory);
        HiringService hiring = new HiringService(llm, RoleTemplates.load(json), staffing);
        catalog.register("hr", deps -> new HrToolkit(hiring));

        // 9. 班次反应与时钟线程
        ShiftDirector director = new ShiftDirector(team, clock, gate, schedule, router, feed);
        ClockPolicy policy = new DefaultClockPolicy(config.schedule().fastForwardIdleMillis());
        ClockDriver driver = new ClockDriver(clock, schedule, policy, router, team, director,
                new ClockDriver.ClockOptions(BUSY_POLL_MILLIS, IDLE_POLL_MILLIS,
                        config.schedule().wrapUpGraceMillis()));

        // 10. 顶层门面
        Company company = new Company(team, clock, driver, router, schedule, director, gate,
                staffing, new JsonSnapshotStore(paths, json));
        logger.info("公司组装完成：{} 个工具包，LLM={}",
                catalog.ids().size(), llm.getClass().getSimpleName());
        return company;
    }

    /** 由配置解析出 endpoint 并构造唯一的 LLM 实现。 */
    private LlmClient buildLlm(LifecycleGate gate) {
        if (llmOverride != null) {
            return llmOverride;
        }
        ProviderResolver.Endpoint endpoint = ProviderResolver.resolve(config.llm(), new ProviderCatalog());
        OpenAiClient client = new OpenAiClient(endpoint, config.llm().retry(),
                RetryArbiter.forEndpoint(endpoint.baseUrl()));
        // 全局暂停门：暂停时 LLM 调用原地等待，而不是报错
        client.setPausedGate(gate::paused);
        // 余额不足：暂停整个公司并说明原因，避免每个角色各报一次同样的错
        client.setOnInsufficientBalance(reason -> {
            if (!gate.paused()) {
                gate.pause("LLM 余额不足，请在充值后恢复：" + reason);
            }
        });
        return client;
    }

    /** Build the optional embedding adapter used by conversation forgetting. */
    private EmbeddingModel buildEmbeddings() {
        String model = config.llm().embeddingModel();
        if (llmOverride != null || model == null || model.isBlank()) {
            return null;
        }
        ProviderResolver.Endpoint chat = ProviderResolver.resolve(config.llm(), new ProviderCatalog());
        ProviderResolver.Endpoint endpoint = new ProviderResolver.Endpoint(
                chat.baseUrl(), chat.apiKey(), model.trim());
        return new EmbeddingClient(endpoint,
                Duration.ofSeconds(Math.max(1, config.llm().retry().timeoutSeconds())));
    }

    /** 邮箱落信 → 给收件人投一条 NEW_MAIL 定向事件。 */
    private void wireMailNotifications(FileMailbox mail, Team team, EventRouter router) {
        mail.onDelivery((message, recipientAddress) -> {
            // 自己发给自己：邮件照常落信，但不产生 NEW_MAIL 通知（对齐 master MailService 语义）
            if (message.fromEmail() != null && message.fromEmail().equalsIgnoreCase(recipientAddress)) {
                logger.debug("自寄邮件（{}），只落信不通知", recipientAddress);
                return;
            }
            for (RoleSpec spec : team.specs()) {
                if (mail.addressOf(spec).equalsIgnoreCase(recipientAddress)) {
                    router.publish(AgentEvent.toRole(spec.id(), EventKind.NEW_MAIL, Priority.NORMAL,
                            Payload.of("mail_id", message.id().value())
                                    .with("from", message.fromEmail())
                                    .with("from_name", message.fromName())
                                    .with("subject", message.subject())
                                    .with("text", message.body())));
                    return;
                }
            }
            logger.debug("收到发往 {} 的邮件，但花名册里没有对应角色", recipientAddress);
        });
    }

    /**
     * 延迟工具箱：{@link ToolLoop} 在 Agent 之前构造，而真正的 Toolbox 要等
     * {@code Agent.start()} 调用 {@code ToolboxFactory} 才存在。
     *
     * <p>这是有意保留的一处"先有鸡还是先有蛋"的接缝，但它只是**转发**：
     * 不持有状态、不做决策，因此不构成依赖环。
     */
    private static final class DeferredToolbox implements Toolbox {

        private volatile Toolbox delegate;

        void attach(Toolbox box) {
            this.delegate = box;
        }

        @Override
        public List<ToolSpec> specs() {
            Toolbox box = delegate;
            return box == null ? List.of() : box.specs();
        }

        @Override
        public com.agent.software.tool.spi.ToolResult invoke(String toolName, Payload arguments) {
            Toolbox box = delegate;
            if (box == null) {
                return com.agent.software.tool.spi.ToolResult.error("工具箱尚未装配完成");
            }
            return box.invoke(toolName, arguments);
        }
    }
}
