package com.agent.software.tool.talk;

import com.agent.software.agent.AgentDirectory;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;
import com.agent.software.sim.event.Priority;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolkit;
import com.agent.software.tool.talk.TeamChannel.TalkMessage;
import com.agent.software.transcript.Transcript;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 角色间沟通工具包（id {@code "talk"}），暴露工具：talk（组内私聊/委派等待）与 list_roles（花名册只读视图）。
 *
 * <p>组内限制沿用 master {@code TalkTo}：只有发送方与接收方都有组、且组名不同才拒绝，提示改用邮件；
 * 无组的新人不受限制。{@code talk} 轨迹的写入统一由 {@link TalkService} 负责（唯一写入点），
 * 因此这里注入的 {@link Transcript} 仅作构造期依赖占位，不在工具层重复记录。
 */
public final class TalkToolkit implements Toolkit {

    private static final String URGENCY_VALUES = "LOW / NORMAL / HIGH / EMERGENCY";

    private final TeamChannel team;
    private final AgentDirectory directory;
    private final Transcript transcript;

    public TalkToolkit(TeamChannel team, AgentDirectory directory, Transcript transcript) {
        this.team = team;
        this.directory = directory;
        this.transcript = transcript;
    }

    @Override
    public String id() {
        return "talk";
    }

    @Override
    public List<Tool> instantiate() {
        return List.of(new TalkTool(), new ListRolesTool());
    }

    // ── talk ───────────────────────────────────────────────────

    private final class TalkTool implements Tool {
        @Override
        public ToolSpec spec() {
            return new ToolSpec("talk",
                    "给同组同事发消息或委派任务。talk 仅限组内沟通，跨组请用 send_email。"
                            + "person 用 list_roles 里的成员姓名；wait=true 表示同步等待对方回复。",
                    JsonSchema.object()
                            .string("person", "目标成员姓名（先用 list_roles 查看花名册）。")
                            .string("message", "要发送的消息或委派的任务，描述具体一些。")
                            .bool("wait", "（可选，默认 false）是否等待对方回复：true = 同步等待，拿到回复再继续。")
                            .enumeration("urgency", "（可选）紧急度，默认 NORMAL。",
                                    List.of("LOW", "NORMAL", "HIGH", "EMERGENCY"))
                            .required("person", "message"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            RoleSpec caller = caller(agent);
            String person = arguments.stringOr("person", "").trim();
            // 兼容 master 的 target 命名，但对外只声明 person。
            if (person.isEmpty()) {
                person = arguments.stringOr("target", "").trim();
            }
            String message = arguments.stringOr("message", "");
            if (person.isEmpty()) {
                return ToolResult.error("talk：缺少目标成员参数 person。");
            }
            if (Text.isBlank(message)) {
                return ToolResult.error("talk：缺少消息内容参数 message。");
            }
            Optional<RoleSpec> found = team.findByName(person);
            if (found.isEmpty()) {
                return ToolResult.error("talk：团队里找不到 \"" + person
                        + "\"。请先调用 list_roles 查看当前成员姓名，再用成员姓名发送。");
            }
            RoleSpec target = found.get();

            // 组内限制：双方都明确有组且不同组才拒绝（对齐 master TalkTo）。
            String callerGroup = Text.orEmpty(caller.group()).trim();
            String targetGroup = Text.orEmpty(target.group()).trim();
            if (!callerGroup.isEmpty() && !targetGroup.isEmpty() && !callerGroup.equals(targetGroup)) {
                return ToolResult.error("talk：talk 工具只用于同组沟通。"
                        + caller.name() + " 属于 \"" + callerGroup + "\"，"
                        + target.name() + " 属于 \"" + targetGroup + "\"。"
                        + "跨组沟通请改用邮件工具 send_email（可用 mail_address_book 查询对方邮箱地址）。");
            }

            Priority urgency = Priority.parse(arguments.stringOr("urgency", "NORMAL"));
            boolean wait = arguments.boolOr("wait", false);
            String group = !callerGroup.isEmpty() ? callerGroup : targetGroup;
            TalkMessage talkMessage = new TalkMessage(caller.id(), caller.name(),
                    target.id(), target.name(), group, message, urgency.name());

            if (!wait) {
                team.send(talkMessage);
                return ToolResult.ok("talk：消息已发送给 " + target.name()
                        + "（紧急度 " + urgency.name() + "）。如需对方回复，请用 wait=true 重发。");
            }
            Optional<String> reply = team.sendAndWait(talkMessage, null, null);
            if (reply.isEmpty()) {
                return ToolResult.error("talk：" + target.name()
                        + " 没有返回回复（可能已下班、不在岗或正在处理其他任务），请稍后再试，或改用 wait=false 的普通消息。");
            }
            return ToolResult.ok("talk：收到 " + target.name() + " 的回复：" + reply.get());
        }
    }

    // ── list_roles ─────────────────────────────────────────────

    private final class ListRolesTool implements Tool {
        @Override
        public ToolSpec spec() {
            return new ToolSpec("list_roles",
                    "查看当前团队成员（姓名、职责、组别、技能）。发消息前先用它确认成员姓名。",
                    JsonSchema.object()
                            .string("group", "（可选）只看某个组，例如 \"Backend Development Group\"。"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String filter = arguments.stringOr("group", "").trim().toLowerCase(Locale.ROOT);
            List<RoleSpec> members = new ArrayList<>();
            for (RoleSpec spec : team.roster()) {
                if (filter.isEmpty()
                        || Text.orEmpty(spec.group()).toLowerCase(Locale.ROOT).contains(filter)) {
                    members.add(spec);
                }
            }
            if (members.isEmpty()) {
                return ToolResult.ok(filter.isEmpty()
                        ? "list_roles：（当前没有团队成员）"
                        : "list_roles：没有匹配组 \"" + arguments.stringOr("group", "") + "\" 的成员。");
            }
            List<String> lines = new ArrayList<>();
            lines.add("list_roles：当前团队成员（" + members.size() + " 人）：");
            for (RoleSpec spec : members) {
                String resp = !Text.isBlank(spec.responsibilities()) ? spec.responsibilities()
                        : (!Text.isBlank(spec.title()) ? spec.title() : "团队成员");
                String group = Text.isBlank(spec.group()) ? "未分组" : spec.group().trim();
                List<String> skills = spec.skills() == null ? List.of() : spec.skills();
                String skillText = skills.size() > 4 ? String.join(", ", skills.subList(0, 4)) : String.join(", ", skills);
                lines.add("  - **" + spec.name() + "** — " + resp + "（组：" + group + "）技能：" + skillText);
            }
            return ToolResult.ok(String.join("\n", lines));
        }
    }

    // ── 辅助 ───────────────────────────────────────────────────

    private RoleSpec caller(RoleId roleId) {
        return directory.spec(roleId).orElseThrow(() ->
                new DomainError("talk.role.unknown", "当前角色不存在于花名册: " + roleId.value()));
    }
}
