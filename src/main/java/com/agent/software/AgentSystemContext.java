package com.agent.software;

import com.agent.software.computers.ComputerManager;
import com.agent.software.event.TimeEventBus;
import com.agent.software.services.MailService;
import com.agent.software.store.ConfigStore;
import com.agent.software.tools.toolkits.client.ClientCommunicationLock;
import com.agent.software.tools.toolkits.mcp.MCPManager;
import com.agent.software.tools.toolkits.skill.SkillManager;
import com.agent.software.web.ChatStore;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 单套 {@link AgentSystem} 的运行时上下文 — 该系统专属的协作对象与数据目录.
 *
 * <p>历史设计里 {@link AgentSystem} 依赖一批进程级全局单例 (ComputerManager /
 * MailService / MCPManager / SkillManager / ClientCommunicationLock / 默认时钟),
 * 导致同一进程内只能安全运行一个 AgentSystem: 第二个系统会与第一个系统共享
 * 电脑注册表、邮箱、工具安装记账、与甲方对话锁、默认时钟与数据文件, 产生
 * 角色/电脑互相覆盖、事件串扰、存档互踩等不可预知异常 (见 docs/agent-system-multi-instance.md).
 *
 * <p>本类把这些协作对象改为<b>每系统一个实例</b>并显式打包: {@code AgentSystem}
 * 在构造时创建自己的 context (默认数据目录 {@code ./data}), 并贯穿注入到
 * {@code RolePool → AgentRole → 工具类}. 多实例用法:
 * <pre>{@code
 * AgentSystem a = new AgentSystem(AgentSystemContext.create(Paths.get("data/company-a")), ...);
 * AgentSystem b = new AgentSystem(AgentSystemContext.create(Paths.get("data/company-b")), ...);
 * }</pre>
 * 每个系统使用独立的时钟、电脑注册表、邮箱、工具管理器与数据目录, 互不干扰.
 *
 * <p>仍属进程级共享 (有意为之): 角色模板注册表 (RoleLoader.TEMPLATES)、podman
 * 网络/镜像/容器名等宿主机基础设施、默认 LLM 配置 (ConfigStore 默认路径).
 */
public final class AgentSystemContext {

    // ── 每系统独立实例 ──────────────────────────────────────

    /** 本系统共享时间源 (作息时钟 + 事件总线). */
    public final TimeEventBus timeManager;

    /** 本系统配置存储 (LLM 分层解析用). */
    public final ConfigStore configStore;

    /** 本系统角色电脑注册表 (role_id → Computer). */
    public final ComputerManager computerManager;

    /** 本系统公司邮箱 (数据落 dataDir/mail). */
    public final MailService mailService;

    /** 本系统 MCP 工具管理器 (工具安装记账按角色隔离). */
    public final MCPManager mcpManager;

    /** 本系统技能库管理器 (数据落 dataDir/skills). */
    public final SkillManager skillManager;

    /** 本系统与甲方沟通互斥锁 (跨系统互不阻塞). */
    public final ClientCommunicationLock clientLock;

    /** 本系统聊天消息存储 + 甲方对话协调 (Web 界面数据源). */
    public final ChatStore chatStore;

    /** 本系统数据根目录 (默认 ./data), 全部持久化文件都落在其下. */
    private final Path dataDir;

    private AgentSystemContext(TimeEventBus timeManager, ConfigStore configStore,
                               ComputerManager computerManager, MailService mailService,
                               MCPManager mcpManager, SkillManager skillManager,
                               ClientCommunicationLock clientLock, ChatStore chatStore, Path dataDir) {
        this.timeManager = timeManager;
        this.configStore = configStore;
        this.computerManager = computerManager;
        this.mailService = mailService;
        this.mcpManager = mcpManager;
        this.skillManager = skillManager;
        this.clientLock = clientLock;
        this.chatStore = chatStore;
        this.dataDir = dataDir;
    }

    /**
     * 创建一个完整独立的系统上下文: 所有协作对象均为新实例, 数据目录为 dataDir
     * (null 时回退 {@code ./data}), 各持久化子目录以 dataDir 为根.
     */
    public static AgentSystemContext create(Path dataDir) {
        Path root = dataDir != null ? dataDir : Paths.get("data");
        return new AgentSystemContext(
                new TimeEventBus(),
                new ConfigStore(),
                new ComputerManager(),
                new MailService(null, root.resolve("mail").toString()),
                new MCPManager(),
                new SkillManager(root.resolve("skills").toString()),
                new ClientCommunicationLock(),
                new ChatStore(),
                root);
    }

    /** 默认上下文: 数据目录 {@code ./data} (与历史布局一致). */
    public static AgentSystemContext createDefault() {
        return create(Paths.get("data"));
    }

    // ── 数据目录 (全部以 dataDir 为根) ───────────────────────

    public Path dataDir() {
        return dataDir;
    }

    /** 角色活动日志目录. */
    public Path journalDir() {
        return dataDir.resolve("journals");
    }

    /** 角色笔记/每日总结目录. */
    public Path notesDir() {
        return dataDir.resolve("notes");
    }

    /** 角色待办清单目录. */
    public Path todosDir() {
        return dataDir.resolve("todos");
    }

    /** 公司邮箱数据目录. */
    public Path mailDir() {
        return dataDir.resolve("mail");
    }

    /** 角色个人电脑目录 (local 模拟用). */
    public Path computersDir() {
        return dataDir.resolve("computers");
    }

    /** 企业云盘挂载目录. */
    public Path driveDir() {
        return dataDir.resolve("drive");
    }

    /** 技能库目录. */
    public Path skillsDir() {
        return dataDir.resolve("skills");
    }

    /** 全量状态存档文件. */
    public Path stateFile() {
        return dataDir.resolve("state.json");
    }

    @Override
    public String toString() {
        return "AgentSystemContext{dataDir=" + dataDir + "}";
    }
}
