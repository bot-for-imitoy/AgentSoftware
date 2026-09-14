package com.agent.software.tools.builtin;

import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ComputerPort;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.Toolkit;
import com.agent.software.tools.spi.Tools;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The {@code pc} toolkit: operate the role's own computer (podman/ssh/local)
 * through {@link ComputerPort}.
 */
public final class PcToolkit {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);
    private static final int OUTPUT_LIMIT = 2000;

    private PcToolkit() {
    }

    /**
     * @param computers  per-role computer lookup (lazy: allocating a podman
     *                   container happens on first use)
     * @param lanDevices optional LAN roster supplier
     */
    public static Toolkit create(Function<RoleId, Optional<ComputerPort>> computers,
                                 Supplier<List<Map<String, String>>> lanDevices) {
        Tool run = Tools.of("run_command", "Run a shell command on your own computer",
                JsonSchema.builder()
                        .required("command", JsonSchema.Property.string("The command to run on your computer."))
                        .build(),
                (role, call) -> {
                    String command = Tools.argStripped(call, "command");
                    if (command.isEmpty()) {
                        return ToolResult.error("run_command: Error: needs a command");
                    }
                    Optional<ComputerPort> pc = computers.apply(role);
                    if (pc.isEmpty()) {
                        return ToolResult.error("run_command: Error: no computer is assigned to " + role);
                    }
                    ComputerPort.ExecResult result = pc.get().exec(command, COMMAND_TIMEOUT, OUTPUT_LIMIT);
                    return result.ok() ? ToolResult.success(result.output()) : ToolResult.error(result.output());
                });

        Tool status = Tools.of("computer_status", "Show your computer's status",
                JsonSchema.object(),
                (role, call) -> computers.apply(role)
                        .map(pc -> ToolResult.success(pc.describe()))
                        .orElseGet(() -> ToolResult.error("computer_status: no computer is assigned to " + role)));

        Tool reboot = Tools.of("reboot", "Reboot your computer",
                JsonSchema.object(),
                (role, call) -> computers.apply(role).map(pc -> {
                    String off = pc.powerOff();
                    String on = pc.powerOn();
                    return ToolResult.success("reboot:\n- " + off + "\n- " + on);
                }).orElseGet(() -> ToolResult.error("reboot: no computer is assigned to " + role)));

        Tool lan = Tools.of("lan_devices", "List the company's computer devices on the LAN",
                JsonSchema.object(),
                (role, call) -> {
                    if (lanDevices == null) {
                        return ToolResult.error("lan_devices: Error: the LAN roster is unavailable");
                    }
                    List<Map<String, String>> devices = lanDevices.get();
                    if (devices == null || devices.isEmpty()) {
                        return ToolResult.success("lan_devices: (no computer devices yet)");
                    }
                    StringBuilder sb = new StringBuilder("lan_devices:");
                    for (Map<String, String> d : devices) {
                        sb.append("\n- ").append(d.get("person")).append(" (").append(d.get("role_id"))
                                .append(") | ").append(d.get("computer")).append(" | ").append(d.get("ip"));
                    }
                    return ToolResult.success(sb.toString());
                });

        return new Toolkit("pc", "Personal computer toolkit: run commands, check status, reboot, list LAN devices",
                List.of(run, status, reboot, lan));
    }
}
