package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.computers.Computer;
import com.maf.scheduler.core.ComputerManager;
import com.maf.scheduler.utils.Json;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.core.ToolRegistry.ToolHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人电脑工具类 (Computer ToolKit) — Python 版 computer_toolkit.py.
 *
 * 包含: run_command / computer_status / lan_devices / reboot.
 */
public final class ComputerToolkit {

    private ComputerToolkit() {
    }

    /** 创建个人电脑工具类. */
    public static ToolKit createComputerToolkit() {
        ToolKit tk = new ToolKit("computer", "个人电脑工具: 运行命令, 运行 MCP 工具");

        ToolHandler runCommand = args -> {
            String cmd = Json.str(args, "command", "").strip();
            if (cmd.isEmpty()) {
                return "错误: 'command' 为必填参数.";
            }
            return computer(tk).runCommand(cmd, 60, 2000);
        };

        ToolHandler computerStatus = args -> computer(tk).describe();

        ToolHandler reboot = args -> computer(tk).reboot();

        ToolHandler lanDevices = args -> {
            List<Map<String, String>> devices = ComputerManager.getInstance().listLanDevices();
            if (devices.isEmpty()) {
                return "(内网暂无电脑设备)";
            }
            List<String> lines = new ArrayList<>();
            for (Map<String, String> d : devices) {
                lines.add("- " + d.get("person") + " (" + d.get("role_id") + ") | 电脑 "
                        + d.get("computer") + " | " + d.get("ip"));
            }
            return "内网电脑设备 (网络 maf-net):\n" + String.join("\n", lines);
        };

        Map<String, Object> commandSchema = new LinkedHashMap<>();
        commandSchema.put("type", "object");
        commandSchema.put("properties", Map.of(
                "command", TalkToolkit.mapOf("string", "要运行的命令")));
        commandSchema.put("required", List.of("command"));

        tk.addPythonTool("run_command",
                "在你自己个人的电脑上运行一条命令 (如 ls, cat, python, git 等), 返回命令输出. "
                        + "适合查看电脑上的文件、执行脚本、检查项目状态.",
                commandSchema, runCommand);
        tk.addPythonTool("computer_status",
                "查看你个人电脑的状态: 是否开机, 工作目录在哪里, 电脑类型.",
                TalkToolkit.emptySchema(), computerStatus);
        tk.addPythonTool("lan_devices",
                "查看内网电脑设备列表: 每个人名, 电脑名称, 内网 IP. "
                        + "各角色电脑在同一桥接网络 (maf-net) 中, 可据此找到其他电脑并通信.",
                TalkToolkit.emptySchema(), lanDevices);
        tk.addPythonTool("reboot",
                "重启你的个人电脑 (关机后自动开机). 适合清理运行状态或安装工具后重启.",
                TalkToolkit.emptySchema(), reboot);
        return tk;
    }

    private static Computer computer(ToolKit tk) {
        AgentRole role = (AgentRole) tk.require("role", "角色");
        return role.computer();  // 角色添加时自动创建 (默认 Podman)
    }

    /** 将当前角色绑定到 computer 工具类. */
    public static void bindComputerToToolkit(ToolKit toolkit, AgentRole role) {
        toolkit.bind("role", role);
    }
}
