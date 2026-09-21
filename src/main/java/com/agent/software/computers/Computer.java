package com.agent.software.computers;

import com.agent.software.role.Role;
import com.agent.software.utils.UUIDObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Computer extends UUIDObject {

    private static final Logger logger = LoggerFactory.getLogger(Computer.class);

    public static final String COMPUTERS_ROOT = "./data/computers";
    public static final String DRIVE_ROOT = "./data/drive";
    public static final String DEFAULT_IMAGE = "agentsoftware-base:latest";
    public static final String CONTAINERFILE = "Containerfile";

    protected boolean ison = false;
    public final MCPServer mcpServer;
    public final Role role;

    protected Computer(Role role) {
        this.mcpServer = null;
        this.role = role;
    }

    protected Computer(Role role, String uuid){
        super(uuid);
        this.mcpServer = null;
        this.role = role;
    }

    public abstract void powerOn();

    public abstract void powerOff();

    public void reboot(){
        powerOff();
        powerOn();
    }

    public boolean isOn(){
        return this.ison;
    }

    public abstract String runCommand(String command, int timeout, int maxChars);

    public abstract String readFile(String path);

    public abstract void writeFile(String path, String content);

    public abstract String listDir(String path);

    public abstract String deleteFile(String path);

}
