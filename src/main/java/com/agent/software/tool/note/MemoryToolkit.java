package com.agent.software.tool.note;

import com.agent.software.agent.AgentControl;
import com.agent.software.agent.AgentState;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.sim.clock.Clock;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆工具包（id {@code "memory"}），暴露工具：summary（保存当日总结、转 OFF_DUTY、关闭当日对话，并关闭个人电脑）。
 *
 * <p>与 master 的 {@code toolkits.memory.Summary} 对齐：总结先落盘，再做下线动作。
 * 后续动作各自独立 try/catch：某一步失败不会回滚已保存的总结，返回的错误里会明确说明
 * "总结已保存，但哪一步失败"，避免向模型谎报总结失败。
 */
public final class MemoryToolkit implements Toolkit {

    private static final Logger log = LoggerFactory.getLogger(MemoryToolkit.class);

    private final NoteBook notes;
    private final Clock clock;
    private final AgentControl control;

    public MemoryToolkit(NoteBook notes, Clock clock, AgentControl control) {
        this.notes = notes;
        this.clock = clock;
        this.control = control;
    }

    @Override
    public String id() {
        return "memory";
    }

    @Override
    public List<Tool> instantiate() {
        return List.of(new Summary());
    }

    /** summary：保存当日总结并完成下线流程。 */
    private final class Summary implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("summary",
                    "总结今天的工作并下线：保存当日总结（次日自动注入系统提示词），转为 OFF_DUTY、关闭当日对话、关闭个人电脑。",
                    JsonSchema.object()
                            .string("content", "今日工作总结正文：已完成工作、关键决策、未完成事项")
                            .required("content"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String content = Text.orEmpty(arguments.stringOr("content", "")).strip();
            if (Text.isBlank(content)) {
                return ToolResult.error("summary: 缺少总结内容（content）");
            }
            int day = clock.nowDay().day();

            try {
                notes.saveSummary(agent, day, content);
            } catch (RuntimeException e) {
                log.warn("[{}] 第 {} 天总结保存失败", agent.value(), day, e);
                return ToolResult.error("summary: 第 " + day + " 天总结保存失败: " + e.getMessage());
            }

            List<String> failures = new ArrayList<>();
            try {
                control.transitionTo(AgentState.OFF_DUTY);
            } catch (RuntimeException e) {
                failures.add("转 OFF_DUTY 失败: " + e.getMessage());
            }
            try {
                control.closeDayConversation(day);
            } catch (RuntimeException e) {
                failures.add("关闭当日对话失败: " + e.getMessage());
            }
            try {
                control.powerOffComputer();
            } catch (RuntimeException e) {
                failures.add("关闭个人电脑失败: " + e.getMessage());
            }

            if (!failures.isEmpty()) {
                log.warn("[{}] 第 {} 天总结已保存，但下线流程未全部完成: {}", agent.value(), day, failures);
                return ToolResult.error("summary: 第 " + day + " 天总结已保存，但后续步骤未全部完成："
                        + String.join("；", failures));
            }
            return ToolResult.ok("summary: 第 " + day + " 天总结已保存。已转为 OFF_DUTY、关闭当日对话、关闭个人电脑。");
        }
    }
}
