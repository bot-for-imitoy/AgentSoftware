package com.agent.software.demo;

import com.agent.software.core.MCPServer;

import java.util.List;
import java.util.Map;

/**
 * MCP demo (Python version mcp_demo.py): MCP filesystem installation + tool invocation.
 */
public final class McpDemo {

    private static final String BOLD = "\033[1m";
    private static final String GREEN = "\033[32m";
    private static final String CYAN = "\033[36m";
    private static final String RESET = "\033[0m";

    private McpDemo() {
    }

    private static void header(String text) {
        System.out.println("\n" + BOLD + CYAN + "═".repeat(60) + RESET);
        System.out.println(BOLD + CYAN + "  " + text + RESET);
        System.out.println(BOLD + CYAN + "═".repeat(60) + RESET + "\n");
    }

    public static void main(String[] args) {
        header("MCP Filesystem Demo");

        // 1. Start the filesystem MCP server (npx -y @modelcontextprotocol/server-filesystem <dir>)
        String workDir = args.length > 0 ? args[0] : "data/computers/mcp_demo";
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(workDir));
        } catch (Exception e) {
            System.out.println("Failed to create the demo directory: " + e.getMessage());
        }
        MCPServer server = new MCPServer("@modelcontextprotocol/server-filesystem", List.of(workDir));
        server.connect();
        if (!server.isAlive(10.0)) {
            System.out.println(RED() + "Error: failed to connect to the MCP server (requires node/npx; the package is downloaded on first start)." + RESET);
            return;
        }
        System.out.println(GREEN() + "MCP server connected: " + server.packageName + RESET);
        System.out.println("Available tools: " + server.listTools().stream()
                .map(m -> String.valueOf(m.get("name"))).toList());

        // 2. Call write_file to write a file
        Map<String, Object> args1 = new java.util.LinkedHashMap<>();
        args1.put("path", workDir + "/hello.txt");
        args1.put("content", "Hello from Java MCP demo!\n");
        String r1 = server.callTool("write_file", args1);
        System.out.println("\nwrite_file → " + r1);

        // 3. Call read_file to read it back
        Map<String, Object> args2 = new java.util.LinkedHashMap<>();
        args2.put("path", workDir + "/hello.txt");
        String r2 = server.callTool("read_file", args2);
        System.out.println("read_file → " + r2);

        server.close();
        System.out.println("\n" + BOLD + GREEN + "MCP Demo Complete." + RESET + "\n");
    }

    private static String GREEN() {
        return GREEN;
    }

    private static String RED() {
        return "\033[31m";
    }
}
