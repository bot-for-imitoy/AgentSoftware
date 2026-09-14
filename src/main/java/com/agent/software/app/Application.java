package com.agent.software.app;

import com.agent.software.adapters.computer.ComputerAdapters;
import com.agent.software.adapters.llm.OpenAiCompatibleClient;
import com.agent.software.adapters.llm.ProviderEndpointResolver;
import com.agent.software.adapters.mail.MailServiceAdapter;
import com.agent.software.adapters.persistence.JsonNoteRepository;
import com.agent.software.adapters.persistence.JsonTodoRepository;
import com.agent.software.adapters.trace.ChatTraceAdapter;
import com.agent.software.computers.ComputerManager;
import com.agent.software.config.AppConfig;
import com.agent.software.config.AppPaths;
import com.agent.software.domain.AgentState;
import com.agent.software.domain.Event;
import com.agent.software.domain.EventType;
import com.agent.software.domain.Payload;
import com.agent.software.domain.Priority;
import com.agent.software.domain.RoleSpec;
import com.agent.software.domain.ShiftCalendar;
import com.agent.software.kernel.RoleId;
import com.agent.software.llm.RetryArbiter;
import com.agent.software.ports.ClockPort;
import com.agent.software.ports.ComputerPort;
import com.agent.software.ports.EventSink;
import com.agent.software.ports.InputPort;
import com.agent.software.ports.LlmPort;
import com.agent.software.ports.MailPort;
import com.agent.software.ports.NoteRepository;
import com.agent.software.ports.TodoRepository;
import com.agent.software.runtime.AgentRuntime;
import com.agent.software.runtime.ClockService;
import com.agent.software.runtime.DispatchService;
import com.agent.software.runtime.LifecycleCoordinator;
import com.agent.software.runtime.TeamRuntime;
import com.agent.software.runtime.ToolLoop;
import com.agent.software.services.MailService;
import com.agent.software.tools.builtin.ClientToolkit;
import com.agent.software.tools.builtin.EmailToolkit;
import com.agent.software.tools.builtin.HrToolkit;
import com.agent.software.tools.builtin.MemoryToolkit;
import com.agent.software.tools.builtin.NoteToolkit;
import com.agent.software.tools.builtin.PcToolkit;
import com.agent.software.tools.builtin.TalkToolkit;
import com.agent.software.tools.builtin.TaskViewToolkit;
import com.agent.software.tools.builtin.TimeToolkit;
import com.agent.software.tools.builtin.TodoToolkit;
import com.agent.software.tools.spi.ToolkitCatalog;
import com.agent.software.tools.spi.ToolService;
import com.agent.software.web.ChatStore;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Composition root: builds one self-contained "company" from configuration.
 *
 * <p>Every collaborator is instance-scoped — clock, team, tool service, mail,
 * repositories, computer registry, chat store — so several applications can run
 * in one JVM without sharing state. Nothing here decides behaviour; it only wires
 * adapters to ports and runtime services.
 */
public final class Application implements AutoCloseable {

    private final AppConfig config;
    private final AppPaths paths;
    private final InputPort input;

    private final ChatStore chatStore;
    private final MailService mailService;
    private final MailServiceAdapter mail;
    private final NoteRepository notes;
    private final TodoRepository todos;
    private final ComputerManager computers;
    private final Map<RoleId, ComputerPort> computerPorts;

    private final ToolService tools;
    private final ToolkitCatalog catalog;
    private final TeamRuntime team;
    private final DispatchService dispatch;
    private final LifecycleCoordinator lifecycle;
    private final ClockService clock;

    /** Used when no console/web input channel was supplied. */
    private static final InputPort UNAVAILABLE_INPUT = new InputPort() {
        @Override
        public boolean interactive() {
            return false;
        }

        @Override
        public ClientReply ask(ClientQuestion question, Duration timeout) {
            return ClientReply.unavailable("no client input channel is configured");
        }
    };

    private Application(AppConfig config, AppPaths paths, InputPort input, ChatStore chatStore,
                        MailService mailService, MailServiceAdapter mail, NoteRepository notes,
                        TodoRepository todos, ComputerManager computers,
                        Map<RoleId, ComputerPort> computerPorts, ToolService tools,
                        ToolkitCatalog catalog, TeamRuntime team, DispatchService dispatch,
                        LifecycleCoordinator lifecycle, ClockService clock) {
        this.config = config;
        this.paths = paths;
        this.input = input;
        this.chatStore = chatStore;
        this.mailService = mailService;
        this.mail = mail;
        this.notes = notes;
        this.todos = todos;
        this.computers = computers;
        this.computerPorts = computerPorts;
        this.tools = tools;
        this.catalog = catalog;
        this.team = team;
        this.dispatch = dispatch;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    // ── construction ───────────────────────────────────────────────────

    public static Application create(AppConfig config, InputPort input) {
        return create(config, input, null);
    }

    /**
     * Build an application.
     *
     * @param llmOverride when non-null, used instead of the configured provider
     *                    endpoint (embedding and tests)
     */
    public static Application create(AppConfig config, InputPort input, LlmPort llmOverride) {
        AppConfig cfg = config == null ? AppConfig.defaults() : config;
        AppPaths paths = AppPaths.resolve(cfg.storage());
        InputPort effectiveInput = input != null ? input : UNAVAILABLE_INPUT;

        ChatStore chatStore = new ChatStore();
        ChatTraceAdapter trace = new ChatTraceAdapter(chatStore);
        LlmPort llm = llmOverride != null ? llmOverride : buildLlm(cfg);

        MailService mailService = buildMailService(cfg, paths);
        MailServiceAdapter mail = new MailServiceAdapter(mailService);
        NoteRepository notes = new JsonNoteRepository(paths.dataFile("notes"));
        TodoRepository todos = new JsonTodoRepository(paths.dataFile("todos"));
        ComputerManager computers = new ComputerManager();
        Map<RoleId, ComputerPort> computerPorts = new ConcurrentHashMap<>();
        ToolService tools = new ToolService();

        // The role factory needs the catalog, which needs the clock; the clock
        // needs the dispatch service, which needs the team. One-element holders
        // break the cycle; they are filled before the first hire().
        final ToolkitCatalog[] catalogHolder = new ToolkitCatalog[1];
        final ClockPort[] clockHolder = new ClockPort[1];

        TeamRuntime team = new TeamRuntime(spec -> {
            ToolkitCatalog catalog = catalogHolder[0];
            if (catalog == null) {
                throw new IllegalStateException("application used before wiring completed");
            }
            tools.bind(spec.id(), catalog.forSpec(spec));
            return new AgentRuntime(spec, llm, tools, trace,
                    s -> systemPrompt(s, clockHolder[0], notes), ToolLoop.Policy.defaults());
        });

        DispatchService dispatch = new DispatchService(team);
        LifecycleCoordinator lifecycle = new LifecycleCoordinator(team, trace);

        ShiftCalendar calendar = ShiftCalendar.of(
                cfg.schedule().secondsPerTick(),
                cfg.schedule().shiftStartHour(),
                cfg.schedule().shiftEndHour());
        ClockService.ClockOptions clockOptions = new ClockService.ClockOptions(
                30.0,
                cfg.schedule().fastForwardIdleSeconds(),
                cfg.schedule().simSecondsPerRealSecond(),
                cfg.schedule().wrapUpGraceSeconds());

        EventSink sink = event -> {
            if (event.type().equals(EventType.SHIFT_START)) {
                lifecycle.onShiftStart();
            } else if (event.type().equals(EventType.SHIFT_END)) {
                lifecycle.onShiftEnd();
            }
            dispatch.dispatch(event);
        };
        ClockService clock = new ClockService(calendar, LocalDate.now(), sink, lifecycle, clockOptions);
        clockHolder[0] = clock;

        Function<RoleId, Optional<ComputerPort>> computerLookup = id -> team.find(id)
                .map(runtime -> computerPorts.computeIfAbsent(id,
                        key -> ComputerAdapters.open(computers, runtime.spec())));

        RoleSpecFactory roleFactory = new RoleSpecFactory(llm, cfg.toolkits().defaults(),
                () -> team.all().stream().map(AgentRuntime::spec).toList());

        ToolkitCatalog catalog = new ToolkitCatalog()
                .register(NoteToolkit.create(notes))
                .register(TodoToolkit.create(todos))
                .register(TimeToolkit.create(clock,
                        roleId -> team.find(roleId).ifPresent(r -> r.setState(AgentState.IDLE))))
                .register(MemoryToolkit.create(notes, clock,
                        (roleId, day) -> team.find(roleId).ifPresent(r -> r.setState(AgentState.OFF_DUTY))))
                .register(PcToolkit.create(computerLookup, computers::listLanDevices))
                .register(EmailToolkit.create(mail,
                        id -> team.find(id).map(AgentRuntime::spec),
                        () -> team.all().stream().map(AgentRuntime::spec).toList()))
                .register(TalkToolkit.create(team, computerLookup, talk -> chatStore.record(
                        ChatStore.KIND_TALK, talk.group(), talk.fromRole().value(), talk.fromName(),
                        talk.toRole().value(), talk.toName(), talk.text(), talk.urgency(), Map.of())))
                .register(TaskViewToolkit.create(team))
                .register(ClientToolkit.create(effectiveInput,
                        id -> team.find(id).map(AgentRuntime::spec),
                        record -> chatStore.record(ChatStore.KIND_CLIENT, record.group(),
                                record.role().value(), record.name(), "", ChatStore.CLIENT_NAME,
                                record.text(), null, Map.of()),
                        Duration.ofMillis(cfg.web().replyTimeoutMs())))
                .register(HrToolkit.create(roleFactory::create,
                        spec -> team.hire(spec),
                        () -> RoleSpecLoader.fromClasspath(cfg.toolkits().defaults()).all()));
        catalogHolder[0] = catalog;

        if (llm instanceof OpenAiCompatibleClient client) {
            client.setPausedGate(lifecycle::paused);
            client.setOnInsufficientBalance(lifecycle::pause);
        }

        Application app = new Application(cfg, paths, effectiveInput, chatStore, mailService, mail, notes,
                todos, computers, computerPorts, tools, catalog, team, dispatch, lifecycle, clock);
        app.wireMailNotifications();
        return app;
    }

    private static LlmPort buildLlm(AppConfig cfg) {
        OpenAiCompatibleClient.Endpoint endpoint = ProviderEndpointResolver.resolve(cfg.llm());
        AppConfig.Llm.Retry retry = cfg.llm().retry();
        RetryArbiter arbiter = RetryArbiter.forEndpoint(endpoint.baseUrl());
        return new OpenAiCompatibleClient(endpoint,
                new OpenAiCompatibleClient.RetryPolicy(
                        retry.maxAttempts(), retry.delaySeconds(), retry.timeoutSeconds()),
                arbiter);
    }

    private static MailService buildMailService(AppConfig cfg, AppPaths paths) {
        MailService.MailConfig mailConfig = new MailService.MailConfig();
        mailConfig.suffix = cfg.mail().suffix();
        mailConfig.smtpHost = cfg.mail().smtp().host();
        mailConfig.smtpPort = cfg.mail().smtp().port();
        mailConfig.smtpUser = cfg.mail().smtp().user();
        mailConfig.smtpPassword = cfg.mail().smtp().password();
        mailConfig.smtpFrom = cfg.mail().smtp().from();
        mailConfig.useSsl = cfg.mail().smtp().useSsl();
        Path dataDir = paths.dataFile("mail");
        mailConfig.dataDir = dataDir.toString();
        return new MailService(mailConfig, dataDir.toString());
    }

    private void wireMailNotifications() {
        mailService.setDeliveryListener((message, recipientMailbox) -> {
            if (recipientMailbox == null || recipientMailbox.isBlank()) {
                return;
            }
            for (AgentRuntime runtime : team.all()) {
                if (!mail.addressFor(runtime.spec()).equalsIgnoreCase(recipientMailbox)) {
                    continue;
                }
                String sender = message.senderName == null || message.senderName.isBlank()
                        ? message.senderEmail : message.senderName;
                Payload payload = Payload.of("title",
                        "You have a new email from " + sender + " (subject: \""
                                + (message.subject == null ? "" : message.subject)
                                + "\"). Please call read_mail to view it.");
                dispatch.dispatch(Event.toRole("email", EventType.NEW_MAIL, Priority.NORMAL,
                        payload, runtime.id()));
                return;
            }
        });
    }

    // ── lifecycle ──────────────────────────────────────────────────────

    /** Start workers first, then the clock (so SHIFT_START always has consumers). */
    public void start() {
        team.startAll();
        clock.start();
    }

    public void stop() {
        clock.stop();
        team.stopAll();
    }

    @Override
    public void close() {
        stop();
    }

    public void pause(String reason) {
        lifecycle.pause(reason);
    }

    public void resume() {
        lifecycle.resume();
    }

    /** Hire every role listed in the bundled templates. */
    public List<AgentRuntime> hireDefaultRoles() {
        RoleSpecLoader loader = RoleSpecLoader.fromClasspath(config.toolkits().defaults());
        List<AgentRuntime> hired = new ArrayList<>();
        for (RoleSpec spec : loader.all()) {
            hired.add(team.hire(spec));
        }
        return hired;
    }

    /** Convenience: hire one role id from the bundled templates. */
    public AgentRuntime hire(String roleId) {
        RoleSpecLoader loader = RoleSpecLoader.fromClasspath(config.toolkits().defaults());
        return team.hire(loader.require(roleId));
    }

    // ── accessors ──────────────────────────────────────────────────────

    public AppConfig config() {
        return config;
    }

    public AppPaths paths() {
        return paths;
    }

    public InputPort input() {
        return input;
    }

    public ChatStore chatStore() {
        return chatStore;
    }

    public MailPort mail() {
        return mail;
    }

    public MailService mailService() {
        return mailService;
    }

    public NoteRepository notes() {
        return notes;
    }

    public TodoRepository todos() {
        return todos;
    }

    public ComputerManager computers() {
        return computers;
    }

    /** The role's computer, allocated lazily on first use. */
    public Optional<ComputerPort> computerFor(RoleId id) {
        return team.find(id).map(runtime -> computerPorts.computeIfAbsent(id,
                key -> ComputerAdapters.open(computers, runtime.spec())));
    }

    public ToolService tools() {
        return tools;
    }

    public ToolkitCatalog catalog() {
        return catalog;
    }

    public TeamRuntime team() {
        return team;
    }

    public DispatchService dispatcher() {
        return dispatch;
    }

    public LifecycleCoordinator lifecycle() {
        return lifecycle;
    }

    public ClockService clock() {
        return clock;
    }

    // ── system prompt ──────────────────────────────────────────────────

    private static String systemPrompt(RoleSpec spec, ClockPort clock, NoteRepository notes) {
        List<String> parts = new ArrayList<>();
        parts.add("You are " + spec.name() + ", your title is " + spec.title()
                + ", working as the " + spec.id() + " role.");
        if (!spec.personality().isBlank()) {
            parts.add("Personality: " + spec.personality() + ".");
        }
        if (!spec.skills().isEmpty()) {
            parts.add("Skills: " + String.join(", ", spec.skills()) + ".");
        }
        if (clock != null) {
            parts.add("Current company time: " + clock.describe() + ".");
        }
        parts.add("If you have no task, rest; you will be notified automatically when something comes up. "
                + "After finishing a task, report back to whoever assigned it, then rest.");
        parts.add("The company cloud drive is at /mnt/drive; /mnt/drive/Public is shared, "
                + "/mnt/drive/" + spec.username() + " is personal.");
        if (spec.hasGroup()) {
            parts.add("You belong to the " + spec.group() + ". Within the group use talk; "
                    + "for other groups use send_email.");
        }
        if (!spec.systemPromptExtra().isBlank()) {
            parts.add(spec.systemPromptExtra());
        }
        if (notes != null && clock != null) {
            Optional<String> summary = notes.latestSummary(spec.id().value(), clock.day());
            summary.ifPresent(text -> parts.add("[Yesterday's Summary]\n" + text));
        }
        return String.join("\n", parts);
    }
}
