package com.agent.software.tool.time;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.sim.clock.Clock;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolkit;

import java.util.List;

/**
 * 时间工具包（id {@code "time"}），暴露工具：get_time / take_rest。
 *
 * <p>只读时钟；{@code take_rest} 没有可注入的自控端口（{@link Clock} 不能写状态），
 * 因此只回一句说明，不假装改了角色状态——真正的休息语义留给事件循环。
 */
public final class TimeToolkit implements Toolkit {

    private final Clock clock;

    public TimeToolkit(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String id() {
        return "time";
    }

    @Override
    public List<Tool> instantiate() {
        return List.of(new GetTime(), new TakeRest());
    }

    // ── get_time ───────────────────────────────────────────────────

    private final class GetTime implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("get_time", "查看当前模拟时间与班次安排。", JsonSchema.object());
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            return ToolResult.ok("当前时间：" + clock.currentDateTime() + "\n" + clock.describe());
        }
    }

    // ── take_rest ──────────────────────────────────────────────────

    private final class TakeRest implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("take_rest", "表示自己暂时休息；下一个事件（任务/提醒/消息）到达时会继续工作。",
                    JsonSchema.object());
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            return ToolResult.ok("take_rest: 已记录休息；下一个事件到达时会继续工作。");
        }
    }
}
