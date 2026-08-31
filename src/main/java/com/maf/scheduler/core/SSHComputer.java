package com.maf.scheduler.core;

import com.maf.scheduler.computers.Computer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * SSH 远程电脑 — Python 版 SSHComputer.
 *
 * 通过 ssh 在远程主机上执行命令. 需要 host/user 配置. 工作目录: ~/maf-&lt;role_id&gt;/.
 */
public class SSHComputer extends Computer {

    private static final Logger logger = LoggerFactory.getLogger(SSHComputer.class);

    public final String name;     // 角色中文名
    public final String host;
    public final String user;
    public final String keyPath;
    public final int port;

    public SSHComputer(String roleId, String host, String user, String keyPath,
                       int port, boolean autoMcp, String name) {
        super(roleId, autoMcp);
        this.name = name != null ? name : "";
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("SSHComputer 需要 host 参数 (远程主机地址)");
        }
        this.host = host;
        this.user = user;
        this.keyPath = keyPath;
        this.port = port;
    }

    @Override
    public String workdir() {
        return "~/maf-" + (roleId == null || roleId.isEmpty() ? "shared" : roleId);
    }

    /** 执行远程命令, 返回输出文本. */
    protected String ssh(String remoteCmd, int timeout, int maxChars) {
        List<String> cmd = sshBase(remoteCmd);
        try {
            ProcessResult r = runProcess(cmd, null, timeout);
            return formatResult(r, maxChars);
        } catch (Exception e) {
            return "错误: ssh 执行失败 - " + e.getMessage();
        }
    }

    private List<String> sshBase(String remoteCmd) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        cmd.add("-p");
        cmd.add(String.valueOf(port));
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o");
        cmd.add("ConnectTimeout=10");
        if (keyPath != null && !keyPath.isEmpty()) {
            cmd.add("-i");
            cmd.add(keyPath);
        }
        String target = host;
        if (user != null && !user.isEmpty()) {
            target = user + "@" + target;
        }
        cmd.add(target);
        cmd.add("mkdir -p " + workdir() + " && cd " + workdir() + " && " + remoteCmd);
        return cmd;
    }

    @Override
    public String powerOn() {
        // ssh 无"开机"概念, 建立会话即视为开机
        String r = ssh("echo ok", 60, 2000);
        if (r.contains("ok")) {
            on = true;
            return "电脑[" + roleId + "] (ssh " + host + ") 已连接. 工作目录: " + workdir();
        }
        return "错误: 无法连接 " + host + ": " + r;
    }

    @Override
    public String powerOff() {
        on = false;
        return "电脑[" + roleId + "] (ssh) 已断开.";
    }

    @Override
    public String runCommand(String command, int timeout, int maxChars) {
        if (!on) {
            return "错误: 电脑未开机.";
        }
        return ssh(command, timeout, maxChars);
    }

    @Override
    public String readFile(String path) {
        return ssh("cat " + PodmanComputer.shlexQuote(path), 60, 2000);
    }

    @Override
    public String writeFile(String path, String content) {
        if (!on) {
            return "错误: 电脑未开机.";
        }
        String q = PodmanComputer.shlexQuote(path);
        String parent = path.contains("/") ? PodmanComputer.shlexQuote(
                path.substring(0, path.lastIndexOf('/'))) : ".";
        List<String> cmd = sshBase("mkdir -p " + parent + " && cat > " + q);
        try {
            ProcessResult r = runProcess(cmd, content, 60);
            String output = ((r.stdout == null ? "" : r.stdout) + (r.stderr == null ? "" : r.stderr)).strip();
            if (r.returnCode != 0) {
                return "[exit " + r.returnCode + "] " + truncate(output, 2000);
            }
            return output.isEmpty() ? "(无输出)" : truncate(output, 2000);
        } catch (Exception e) {
            return "错误: ssh 执行失败 - " + e.getMessage();
        }
    }

    @Override
    public String listDir(String path) {
        String target = (path == null || path.isEmpty()) ? workdir() : path;
        return ssh("ls -la " + PodmanComputer.shlexQuote(target), 60, 2000);
    }

    @Override
    public String deleteFile(String path) {
        return ssh("rm -f " + PodmanComputer.shlexQuote(path), 60, 2000);
    }
}
