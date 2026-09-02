package com.agent.software.tools.toolkits.pc;

import com.agent.software.computers.ComputerManager;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * lan_devices - view the list of LAN computer devices: each person name, computer name, LAN IP.
 * All role computers are on the same bridged network (maf-net); you can use this to find other computers and communicate with them.
 *
 * The device list comes from the computer registry of the system the role belongs to (one per system); standalone roles
 * without a bound context fall back to the process-level default singleton.
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
