package com.agent.software.demo;

import com.agent.software.core.MCPServer;

import java.util.List;
import java.util.Map;

/**
 * MCP 演示 (Python 版 mcp_demo.py): MCP filesystem 安装 + 工具调用.
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

        // 1. 启动 filesystem MCP 服务器 (npx -y @modelcontextprotocol/server-filesystem <dir>)
        String workDir = args.length > 0 ? args[0] : "data/computers/mcp_demo";
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(workDir));
        } catch (Exception e) {
            System.out.println("创建演示目录失败: " + e.getMessage());
        }
        MCPServer server = new MCPServer("@modelcontextprotocol/server-filesystem", List.of(workDir));
        server.connect();
        if (!server.isAlive(10.0)) {
            System.out.println(RED() + "错误: MCP 服务器连接失败 (需要 node/npx; 首次启动会下载包)." + RESET);
            return;
        }
        System.out.println(GREEN() + "MCP 服务器已连接: " + server.packageName + RESET);
        System.out.println("可用工具: " + server.listTools().stream()
                .map(m -> String.valueOf(m.get("name"))).toList());

        // 2. 调用 write_file 写一个文件
        Map<String, Object> args1 = new java.util.LinkedHashMap<>();
        args1.put("path", workDir + "/hello.txt");
        args1.put("content", "Hello from Java MCP demo!\n");
        String r1 = server.callTool("write_file", args1);
        System.out.println("\nwrite_file → " + r1);

        // 3. 调用 read_file 读回来
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
