package com.agent.software.tools.builtin;

import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ClockPort;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.Toolkit;
import com.agent.software.tools.spi.Tools;

import java.util.List;
import java.util.function.Consumer;

/** The {@code time} toolkit: current shift time and taking a rest. */
public final class TimeToolkit {

    private TimeToolkit() {
    }

    /**
     * @param clock  simulated clock
     * @param onRest invoked when the role takes a rest (the app points it at the runtime)
     */
    public static Toolkit create(ClockPort clock, Consumer<RoleId> onRest) {
        Tool getTime = Tools.of("get_time", "Show the current simulated shift time",
                JsonSchema.object(),
                (role, call) -> ToolResult.success(
                        clock.describe() + "\nCurrent tick: " + clock.tick()));

        Tool takeRest = Tools.of("take_rest", "Go idle and wait for the next event",
                JsonSchema.object(),
                (role, call) -> {
                    if (onRest != null) {
                        onRest.accept(role);
                    }
                    return ToolResult.success("take_rest: rest started (state IDLE). "
                            + "You will be woken up when tasks or events arrive.");
                });

        return new Toolkit("time", "Time toolkit: check the current schedule time, take a rest",
                List.of(getTime, takeRest));
    }
}
