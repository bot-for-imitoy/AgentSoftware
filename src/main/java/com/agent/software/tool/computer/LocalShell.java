package com.agent.software.tool.computer;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.kernel.Ids.RoleId;

import java.time.Duration;
import java.util.List;

/**
 * 本地目录形态的个人电脑：直接以宿主文件系统为工作目录，命令经子进程执行。
 */
public final class LocalShell implements Shell {

    /** 记录角色、目录规格与路径解析器。 */
    public LocalShell(RoleId roleId, RoleSpec.ComputerSpec spec, AppPaths paths) {
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
