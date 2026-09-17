package com.agent.software.tool.client;

import com.agent.software.agent.AgentDirectory;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolkit;
import com.agent.software.transcript.Transcript;

import java.time.Duration;
import java.util.List;

/**
 * 客户沟通工具包（id {@code "client"}），暴露工具：talk_to_client（全局互斥的客户/用户对话通道）。
 *
 * <p><b>身份信息</b>：{@link Tool#invoke} 只给 {@link RoleId}。若在构造期注入
 * {@link AgentDirectory}，就能把调用者的**姓名与组名**一并带进对话（Web UI 上看到的是人名
 * 而不是角色 id，对齐 master）；不注入时退化为用 roleId 当姓名、组名留空。
 *
 * <p><b>全局互斥</b>：同一时刻只能有一位组长在找客户，由 {@link ClientChannel} 实现内部保证
 * （通道实例由 bootstrap 共享）。
 *
 * <p>超时固定为 20 分钟（对齐 master 的客户回复超时）。
 */
public final class ClientToolkit implements Toolkit {

    private static final Duration REPLY_TIMEOUT = Duration.ofMinutes(20);

    private final ClientChannel client;
    private final AgentDirectory directory;
    private final Transcript transcript;

    /** 不注入花名册（单测/嵌入）：姓名退化为 roleId。 */
    public ClientToolkit(ClientChannel client, Transcript transcript) {
        this(client, null, transcript);
    }

    /** 注入花名册：对话里带上调用者的姓名与组名。 */
    public ClientToolkit(ClientChannel client, AgentDirectory directory, Transcript transcript) {
        this.client = client;
        this.directory = directory;
        this.transcript = transcript;
    }

    @Override
    public String id() {
        return "client";
    }

    @Override
    public List<Tool> instantiate() {
        return List.of(new TalkToClientTool());
    }

    private final class TalkToClientTool implements Tool {
        @Override
        public ToolSpec spec() {
            return new ToolSpec("talk_to_client",
                    "与客户（用户）实时沟通：提出问题、确认方案、汇报进度。"
                            + "调用后会暂停并等待客户输入，拿到回复再继续；同一时刻只允许一位同事找客户。",
                    JsonSchema.object()
                            .string("message", "你想对客户说的话（问题或进度汇报）。")
                            .required("message"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String message = arguments.stringOr("message", "").trim();
            if (message.isEmpty()) {
                return ToolResult.error("talk_to_client：缺少 message 参数。");
            }
            String name = agent.value();
            String group = "";
            if (directory != null) {
                com.agent.software.agent.role.RoleSpec spec = directory.spec(agent).orElse(null);
                if (spec != null) {
                    name = Text.isBlank(spec.name()) ? name : spec.name();
                    group = Text.orEmpty(spec.group());
                }
            }
            ClientChannel.ClientQuestion question =
                    new ClientChannel.ClientQuestion(agent, name, group, message);
            record(agent, name, group, message);
            ClientChannel.ClientReply reply = client.ask(question, REPLY_TIMEOUT);
            if (!reply.delivered()) {
                return ToolResult.error("talk_to_client：未能联系到客户 - " + reply.reason());
            }
            String text = Text.orEmpty(reply.text()).strip();
            if (text.isEmpty()) {
                return ToolResult.ok("talk_to_client：客户没有输入任何内容（空回复）。");
            }
            return ToolResult.ok("talk_to_client：客户回复：" + text);
        }
    }

    /** 把客户往来写进轨迹；轨迹写入失败不影响对话本身。 */
    private void record(RoleId agent, String name, String group, String message) {
        if (transcript == null) {
            return;
        }
        try {
            transcript.client(new Transcript.Client(agent, Text.orEmpty(name),
                    Text.orEmpty(group), message));
        } catch (RuntimeException ignored) {
            // 轨迹只是展示层，不能因为它失败而中断客户沟通。
        }
    }
}
