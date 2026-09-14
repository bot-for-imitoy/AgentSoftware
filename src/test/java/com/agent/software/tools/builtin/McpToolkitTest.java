package com.agent.software.tools.builtin;

import com.agent.software.domain.Payload;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ComputerPort;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolResult;
import com.agent.software.ports.ToolSpec;
import com.agent.software.tools.spi.ToolService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolkitTest {

    private static final RoleId CEO = RoleId.of("CEO");

    static final class FakeMcpComputer implements ComputerPort {
        final List<ToolSpec> specs = new ArrayList<>();
        final Map<String, ToolResult> results = new LinkedHashMap<>();

        @Override
        public List<ToolSpec> mcpTools() {
            return List.copyOf(specs);
        }

        @Override
        public ToolResult callMcpTool(String name, Payload arguments) {
            return results.getOrDefault(name, ToolResult.error("no such tool"));
        }

        @Override
        public String powerOn() {
            return "on";
        }

        @Override
        public String powerOff() {
            return "off";
        }

        @Override
        public boolean isOn() {
            return true;
        }

        @Override
        public ExecResult exec(String command, Duration timeout, int maxOutputChars) {
            return new ExecResult("", 0);
        }

        @Override
        public Optional<String> readFile(String path) {
            return Optional.empty();
        }

        @Override
        public String writeFile(String path, String content) {
            return path;
        }

        @Override
        public String listDir(String path) {
            return "";
        }

        @Override
        public String deleteFile(String path) {
            return "";
        }

        @Override
        public String workdir() {
            return "/home/ceo";
        }

        @Override
        public String hostDir() {
            return "/host/ceo";
        }

        @Override
        public String driveRoot() {
            return "/mnt/drive";
        }

        @Override
        public String describe() {
            return "Computer [CEO]";
        }
    }

    private static ToolResult call(ToolService service, String tool, Map<String, Object> args) {
        return service.invoke(CEO, new ToolCall("c", tool, Payload.of(args)));
    }

    @Test
    void searchListAddUseAndRemoveMcpTools() {
        FakeMcpComputer computer = new FakeMcpComputer();
        computer.specs.add(new ToolSpec("read_file", "read a file from disk", JsonSchema.object()));
        computer.specs.add(new ToolSpec("browser_navigate", "open a page", JsonSchema.object()));
        computer.results.put("read_file", ToolResult.success("file contents"));

        Function<RoleId, Optional<ComputerPort>> lookup = id -> Optional.of(computer);
        ToolService service = new ToolService();
        service.bind(CEO, List.of(McpToolkit.create(service, lookup)));

        assertTrue(call(service, "mcp_list", Map.of()).text().contains("read_file"));
        assertTrue(call(service, "mcp_search", Map.of("keyword", "browser")).text().contains("browser_navigate"));

        ToolResult added = call(service, "mcp_add", Map.of("tool_name", "read_file"));
        assertTrue(added.ok(), added.text());
        assertTrue(service.hasTool(CEO, "read_file"));

        ToolResult invoked = service.invoke(CEO, new ToolCall("c", "read_file", Payload.of("path", "/tmp/x")));
        assertTrue(invoked.ok());
        assertEquals("file contents", invoked.text());

        assertTrue(call(service, "mcp_my_tools", Map.of()).text().contains("read_file"));

        assertTrue(call(service, "mcp_remove", Map.of("tool_name", "read_file")).ok());
        assertFalse(service.hasTool(CEO, "read_file"));
    }

    @Test
    void addUnknownToolFails() {
        FakeMcpComputer computer = new FakeMcpComputer();
        ToolService service = new ToolService();
        service.bind(CEO, List.of(McpToolkit.create(service, id -> Optional.of(computer))));

        ToolResult result = call(service, "mcp_add", Map.of("tool_name", "ghost"));
        assertFalse(result.ok());
        assertTrue(result.text().contains("no MCP tool named"));
        assertFalse(call(service, "mcp_add", Map.of()).ok());
    }
}
