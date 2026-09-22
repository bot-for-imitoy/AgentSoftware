package com.agent.software.tools.toolkits.pc;

import com.agent.software.computers.ComputerManager;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** lan_devices：查看同网段内的同事电脑。 */
public class LanDevices extends Tool {

    private final ComputerManager manager;

    public LanDevices(ComputerManager manager) {
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
    public String getDescription() {
        return "List colleagues' computers on the same network.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (manager == null) {
            return "lan_devices error: no computer manager";
        }
        List<Map<String, String>> devices = manager.listLanDevices();
        return "lan_devices (" + devices.size() + "): " + Json.stringify(devices);
    }
}
