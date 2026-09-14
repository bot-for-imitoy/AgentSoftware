package com.agent.software.ports;

import com.agent.software.domain.Payload;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * A role's personal computer (podman container, ssh host or local directory).
 *
 * <p>All concrete kinds share this contract; MCP servers exposed by the computer
 * are surfaced as {@link ToolSpec}s and invoked by name.
 */
public interface ComputerPort {

    String powerOn();

    String powerOff();

    boolean isOn();

    ExecResult exec(String command, Duration timeout, int maxOutputChars);

    Optional<String> readFile(String path);

    String writeFile(String path, String content);

    String listDir(String path);

    String deleteFile(String path);

    /** Tools exposed by this computer's MCP sessions. */
    List<ToolSpec> mcpTools();

    ToolResult callMcpTool(String name, Payload arguments);

    String workdir();

    String hostDir();

    /** The shared cloud-drive mount root inside this computer (e.g. {@code /mnt/drive}). */
    String driveRoot();

    String describe();

    /** Result of a command execution. */
    record ExecResult(String output, int exitCode) {
        public ExecResult {
            output = output == null ? "" : output;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }
}
