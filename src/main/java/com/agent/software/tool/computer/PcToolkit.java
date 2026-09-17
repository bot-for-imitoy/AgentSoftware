package com.agent.software.tool.computer;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolkit;

import java.time.Duration;
import java.util.List;

/**
 * 个人电脑工具包（id {@code "pc"}），暴露工具：run_command / computer_status / lan_devices / reboot（经由构造期注入的 {@link Shell} 操作电脑）。
 *
 * <p>只依赖窄端口 {@link Shell}：不再像 master 的 {@code Pc} 那样拿 {@code AgentRole}
 * （可越权改角色状态 / 抓 ComputerManager 单例）。
 *
 * <p>契约缺口：master 的 {@code lan_devices} 数据来自宿主机侧 {@code ComputerManager}
 * 的角色注册表（人名 / 容器名 / 网桥 IP），{@code Shell} 端口给不出兄弟电脑清单，
 * 这里退化为在电脑内部执行"网卡地址 + 邻居表"探测（best-effort）。若需要 master 的完整清单，
 * 应在装配层把 {@code ShellRegistry} 注入本工具包。
 */
public final class PcToolkit implements Toolkit {

    /** run_command 默认超时（秒），对齐 master {@code RunCommand}。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** run_command 返回给 LLM 的输出上限（字符）。 */
    private static final int MAX_OUTPUT_CHARS = 2_000;

    private final Shell shell;

    public PcToolkit(Shell shell) {
        this.shell = shell;
    }

    @Override
    public String id() {
        return "pc";
    }

    @Override
    public List<Tool> instantiate() {
        return List.of(new RunCommand(), new ComputerStatus(), new LanDevices(), new Reboot());
    }

    /** 统一把 {@link Shell.CommandResult} 转成工具结果：退出码非 0 即 error，不再靠字符串前缀嗅探。 */
    private ToolResult fromResult(String prefix, Shell.CommandResult result) {
        String text = result.combined() == null ? "" : result.combined().strip();
        if (result.ok()) {
            return ToolResult.ok(text.isEmpty() ? prefix + "：执行成功（无输出）" : text);
        }
        return ToolResult.error(prefix + " 执行失败（退出码 " + result.exitCode() + "）：\n"
                + (text.isEmpty() ? "(无输出)" : text));
    }

    private ToolResult noShell() {
        return ToolResult.error("未注入电脑（Shell），无法执行该操作。");
    }

    /** run_command：在个人电脑上执行一条命令。 */
    private final class RunCommand implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("run_command",
                    "在你的个人电脑上执行一条 shell 命令（如 ls / cat / python / git），返回命令输出。",
                    JsonSchema.object()
                            .string("command", "要在电脑上执行的命令。")
                            .integer("timeout_seconds", "可选，命令超时秒数，默认 " + DEFAULT_TIMEOUT_SECONDS + "。")
                            .required("command"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            if (shell == null) {
                return noShell();
            }
            String command = arguments == null ? "" : arguments.stringOr("command", "").strip();
            if (command.isEmpty()) {
                return ToolResult.error("run_command 需要 command 参数。");
            }
            int seconds = arguments.intValue("timeout_seconds").orElse(DEFAULT_TIMEOUT_SECONDS);
            if (seconds <= 0) {
                seconds = DEFAULT_TIMEOUT_SECONDS;
            }
            return fromResult("run_command", shell.run(command, Duration.ofSeconds(seconds), MAX_OUTPUT_CHARS));
        }
    }

    /** computer_status：查看电脑是否开机、工作目录等。 */
    private final class ComputerStatus implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("computer_status",
                    "查看你的个人电脑状态：是否开机、电脑类型、工作目录与共享云盘位置。",
                    JsonSchema.object());
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            if (shell == null) {
                return noShell();
            }
            return ToolResult.ok(shell.describe());
        }
    }

    /** lan_devices：查看本机在局域网中能看到的设备（best-effort）。 */
    private final class LanDevices implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("lan_devices",
                    "查看本机所在局域网内的设备（网卡地址与邻居表），可用于发现其他电脑。",
                    JsonSchema.object());
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            if (shell == null) {
                return noShell();
            }
            // 末尾 exit 0：探测命令部分缺失时仍返回已采集到的信息，而不是整条命令非 0
            String command = "hostname 2>/dev/null; "
                    + "echo '--- 本机地址 ---'; (ip -4 -o addr show 2>/dev/null || hostname -I 2>/dev/null); "
                    + "echo '--- 邻居设备 ---'; (ip neigh show 2>/dev/null || cat /proc/net/arp 2>/dev/null); "
                    + "exit 0";
            Shell.CommandResult result = shell.run(command, Duration.ofSeconds(30), 4_000);
            String text = result.combined().strip();
            return ToolResult.ok("本机局域网视图（基于网卡地址与邻居表，尽力而为）：\n"
                    + (text.isEmpty() ? "(未采集到数据)" : text));
        }
    }

    /** reboot：关机再开机。 */
    private final class Reboot implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("reboot",
                    "重启你的个人电脑（先关机再开机），可用于清理运行时状态或安装工具后生效。",
                    JsonSchema.object());
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            if (shell == null) {
                return noShell();
            }
            String off = shell.powerOff();
            String on = shell.powerOn();
            String detail = "- " + off + "\n- " + on;
            return shell.poweredOn()
                    ? ToolResult.ok("电脑已重启。\n" + detail)
                    : ToolResult.error("电脑重启失败。\n" + detail);
        }
    }
}
