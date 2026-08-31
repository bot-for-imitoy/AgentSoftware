package com.agent.software.tools.toolkits.pc;

import com.agent.software.computers.ComputerManager;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * lan_devices — 查看内网电脑设备列表: 每个人名, 电脑名称, 内网 IP.
 * 各角色电脑在同一桥接网络 (maf-net) 中, 可据此找到其他电脑并通信.
 *
 * 设备列表取本角色所属系统的电脑注册表 (每系统一份), 未绑定上下文的
 * 独立角色回退到进程级默认单例.
 */
public class LanDevices extends Tool {

    private final ComputerManager manager;

    public LanDevices() {
        this(null);
    }

    public LanDevices(ComputerManager manager) {
        super();
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "lan_devices";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String handler(Map<String, Object> args) {
        ComputerManager cm = manager != null ? manager : ComputerManager.getInstance();
        List<Map<String, String>> devices = cm.listLanDevices();
        if (devices.isEmpty()) {
            return "lan_devices: (no computer devices on the LAN yet)";
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, String> d : devices) {
            lines.add("- " + d.get("person") + " (" + d.get("role_id") + ") | computer "
                    + d.get("computer") + " | " + d.get("ip"));
        }
        return "lan_devices: LAN computer devices (network maf-net):\n" + String.join("\n", lines);
    }
}
