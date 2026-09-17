package com.agent.software.tool.computer;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.kernel.Ids.RoleId;

import java.time.Duration;
import java.util.List;

/**
 * Podman 容器形态的个人电脑：每角色一个容器，命令经 podman exec 执行。
 */
public final class PodmanShell implements Shell {

    /** 记录角色、容器规格、路径解析器与共享网络名。 */
    public PodmanShell(RoleId roleId, RoleSpec.ComputerSpec spec, AppPaths paths, String networkName) {
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
