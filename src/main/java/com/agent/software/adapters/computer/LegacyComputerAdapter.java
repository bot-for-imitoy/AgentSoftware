package com.agent.software.adapters.computer;

import com.agent.software.computers.Computer;
import com.agent.software.core.Types;
import com.agent.software.domain.Payload;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.ports.ComputerPort;
import com.agent.software.ports.ToolResult;
import com.agent.software.ports.ToolSpec;
import com.agent.software.role.ToolRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link ComputerPort} over the legacy {@link Computer} hierarchy, so the runtime
 * is indifferent to podman/ssh/local.
 */
public final class LegacyComputerAdapter implements ComputerPort {

    private final Computer computer;

    public LegacyComputerAdapter(Computer computer) {
        if (computer == null) {
            throw new IllegalArgumentException("computer must not be null");
        }
        this.computer = computer;
    }

    public Computer computer() {
        return computer;
    }

    @Override
    public String powerOn() {
        return computer.powerOn();
    }

    @Override
    public String powerOff() {
        return computer.powerOff();
    }

    @Override
    public boolean isOn() {
        return computer.isOn();
    }

    @Override
    public ExecResult exec(String command, Duration timeout, int maxOutputChars) {
        if (!computer.isOn()) {
            return new ExecResult("Error: computer is not powered on.", -1);
        }
        int seconds = timeout == null ? 60 : (int) Math.max(1, timeout.toSeconds());
        String output = computer.runCommand(command, seconds, maxOutputChars);
        return new ExecResult(output, parseExit(output));
    }

    @Override
    public Optional<String> readFile(String path) {
        String content = computer.readFile(path);
        if (content == null || Types.isFailureText(content)) {
            return Optional.empty();
        }
        return Optional.of(content);
    }

    @Override
    public String writeFile(String path, String content) {
        return computer.writeFile(path, content);
    }

    @Override
    public String listDir(String path) {
        return computer.listDir(path);
    }

    @Override
    public String deleteFile(String path) {
        return computer.deleteFile(path);
    }

    @Override
    public List<ToolSpec> mcpTools() {
        List<ToolSpec> out = new ArrayList<>();
        for (ToolRegistry.ToolDef def : computer.iterMcpTools()) {
            out.add(new ToolSpec(def.name, def.description, JsonSchema.fromMap(def.inputSchema)));
        }
        return out;
    }

    @Override
    public ToolResult callMcpTool(String name, Payload arguments) {
        String result = computer.runMcpTool(name, arguments == null ? Payload.empty().asMap() : arguments.asMap());
        if (result == null || Types.isFailureText(result)) {
            return ToolResult.error(result == null ? "Error: MCP tool '" + name + "' returned no result" : result);
        }
        return ToolResult.success(result);
    }

    @Override
    public String workdir() {
        return computer.workdir();
    }

    @Override
    public String hostDir() {
        return computer.hostDir();
    }

    @Override
    public String describe() {
        return computer.describe();
    }

    /** Parse the legacy {@code [exit N]} marker; absent means success. */
    static int parseExit(String output) {
        if (output == null || !output.startsWith("[exit")) {
            return 0;
        }
        int end = output.indexOf(']');
        if (end < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(output.substring("[exit".length(), end).strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
