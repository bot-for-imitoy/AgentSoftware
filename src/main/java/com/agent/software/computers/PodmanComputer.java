package com.agent.software.computers;

import com.agent.software.role.Role;

public class PodmanComputer extends Computer{

    private void init(){
        // TODO
    }

    public PodmanComputer(Role role) {
        super(role);
        init();
    }

    public PodmanComputer(Role role, String uuid){
        super(role, uuid);
        init();
    }

    @Override
    public void powerOn() {
        // TODO
    }

    @Override
    public void powerOff() {
        // TODO
    }

    @Override
    public String runCommand(String command, int timeout, int maxChars) {
        // TODO
        return "";
    }

    @Override
    public String readFile(String path) {
        // TODO
        return "";
    }

    @Override
    public void writeFile(String path, String content) {
        // TODO
    }

    @Override
    public String listDir(String path) {
        // TODO
        return "";
    }

    @Override
    public String deleteFile(String path) {
        // TODO
        return "";
    }
}
