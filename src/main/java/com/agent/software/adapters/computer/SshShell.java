package com.agent.software.adapters.computer;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.model.RoleSpec;
import com.agent.software.ports.Shell;

import java.time.Duration;
import java.util.List;

/**
 * SSH 远程主机形态的个人电脑：命令与文件操作都经 SSH 通道完成。
 */
public final class SshShell implements Shell {

    /** 记录角色与 SSH 连接规格。 */
    public SshShell(RoleId roleId, RoleSpec.ComputerSpec spec) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public String powerOn() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public String powerOff() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public boolean poweredOn() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public CommandResult run(String command, Duration timeout, int maxOutputChars) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public String readFile(String path) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void writeFile(String path, String content) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<String> listDir(String path) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void deleteFile(String path) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public String workdir() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public String driveRoot() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public String hostDir() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public String describe() {
        throw new UnsupportedOperationException("skeleton");
    }
}
