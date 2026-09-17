package com.agent.software.bootstrap;

import com.agent.software.company.Company;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JsonCodec;
import com.agent.software.llm.LlmClient;
import com.agent.software.tool.client.ClientChannel;
import com.agent.software.transcript.Transcript;
import com.agent.software.transcript.Transcript.Feed;

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
        throw new UnsupportedOperationException("skeleton");
    }

    /** 指定客户输入通道（控制台或 Web）。 */
    public CompanyBuilder withClientChannel(ClientChannel channel) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 指定轨迹实现（默认内存 ChatFeed；测试可换成 fake）。 */
    public CompanyBuilder withTranscript(Transcript.Feed feed) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 组装出可运行的 {@link Company}（含 Web 输入通道所需的 feed）。 */
    public Company build() {
        throw new UnsupportedOperationException("skeleton");
    }
}
