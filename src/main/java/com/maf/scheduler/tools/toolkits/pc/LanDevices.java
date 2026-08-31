package com.maf.scheduler.tools.toolkits.pc;

import com.maf.scheduler.computers.ComputerManager;
import com.maf.scheduler.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * lan_devices — 查看内网电脑设备列表: 每个人名, 电脑名称, 内网 IP.
 * 各角色电脑在同一桥接网络 (maf-net) 中, 可据此找到其他电脑并通信.
 */
public class LanDevices extends Tool {

    public LanDevices() {
        super();
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
        List<Map<String, String>> devices = ComputerManager.getInstance().listLanDevices();
        if (devices.isEmpty()) {
            return "lan_devices: (内网暂无电脑设备)";
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, String> d : devices) {
            lines.add("- " + d.get("person") + " (" + d.get("role_id") + ") | 电脑 "
                    + d.get("computer") + " | " + d.get("ip"));
        }
        return "lan_devices: 内网电脑设备 (网络 maf-net):\n" + String.join("\n", lines);
    }
}
