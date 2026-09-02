package com.agent.software.computers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * SSH remote computer - Python version SSHComputer.
 *
 * Executes commands on a remote host over ssh. Requires host/user configuration. Work directory: ~/maf-&lt;role_id&gt;/.
 */
public class SSHComputer extends Computer {

    private static final Logger logger = LoggerFactory.getLogger(SSHComputer.class);

    public final String name;     // role Chinese name
    public final String host;
    public final String user;
    public final String keyPath;
    public final int port;

    public SSHComputer(String roleId, String host, String user, String keyPath,
                       int port, boolean autoMcp, String name) {
        super(roleId, autoMcp);
        this.name = name != null ? name : "";
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("SSHComputer requires a host parameter (remote host address)");
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

    /** Execute a remote command, returning the output text. */
    protected String ssh(String remoteCmd, int timeout, int maxChars) {
        List<String> cmd = sshBase(remoteCmd);
        try {
            ProcessResult r = runProcess(cmd, null, timeout);
            return formatResult(r, maxChars);
        } catch (Exception e) {
            return "Error: ssh execution failed - " + e.getMessage();
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
        // ssh has no "power on" concept; establishing a session counts as powered on
        String r = ssh("echo ok", 60, 2000);
        if (r.contains("ok")) {
            on = true;
            return "Computer [" + roleId + "] (ssh " + host + ") connected. Work directory: " + workdir();
        }
        return "Error: cannot connect to " + host + ": " + r;
    }

    @Override
    public String powerOff() {
        on = false;
        return "Computer [" + roleId + "] (ssh) disconnected.";
    }

    @Override
    public String runCommand(String command, int timeout, int maxChars) {
        if (!on) {
            return "Error: computer is not powered on.";
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
            return "Error: computer is not powered on.";
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
            return output.isEmpty() ? "(no output)" : truncate(output, 2000);
        } catch (Exception e) {
            return "Error: ssh execution failed - " + e.getMessage();
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
