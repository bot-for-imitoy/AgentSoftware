package com.agent.software.tool.computer;

import java.time.Duration;
import java.util.List;

/**
 * 个人电脑能力（podman 容器 / 本地目录 / SSH 三种实现共用）。
 *
 * <p>注意：这里不再出现 MCP。master 的 {@code Computer} 直接持有
 * {@code MCPServer} 与 {@code ToolRegistry.ToolDef}，使"电脑"这个基础设施概念
 * 反向依赖了工具系统；MCP 已移到 {@code tool.mcp} 包，两边互不认识。
 */
public interface Shell {

    String powerOn();

    String powerOff();

    boolean poweredOn();

    /** 在电脑上执行一条命令。 */
    CommandResult run(String command, Duration timeout, int maxOutputChars);

    String readFile(String path);

    void writeFile(String path, String content);

    List<String> listDir(String path);

    void deleteFile(String path);

    /** 电脑内的工作目录（容器内路径或本地目录）。 */
    String workdir();

    /** 共享云盘挂载根。 */
    String driveRoot();

    /** 宿主机上映射的目录（用于宿主机侧读取模拟产出的文件）。 */
    String hostDir();

    /** 给 LLM 的状态描述。 */
    String describe();

    /** 命令执行结果三元组（替代 master 的 "[exit N] ..." 字符串再解析）。 */
    record CommandResult(int exitCode, String stdout, String stderr) {

        public boolean ok() {
            throw new UnsupportedOperationException("skeleton");
        }

        public String combined() {
            throw new UnsupportedOperationException("skeleton");
        }
    }
}
