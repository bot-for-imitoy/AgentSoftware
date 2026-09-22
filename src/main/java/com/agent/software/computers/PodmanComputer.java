package com.agent.software.computers;

import com.agent.software.role.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Podman 容器电脑：命令经 {@code podman exec} 在容器内执行。
 *
 * <p>容器名 {@code agentsoftware-<role_id>}，和 refactor2 的前缀保持一致。
 */
public class PodmanComputer extends Computer {

    private static final Logger logger = LoggerFactory.getLogger(PodmanComputer.class);

    public final String containerName;

    public PodmanComputer(Role role) {
        super(role);
        this.containerName = "agentsoftware-" + roleId();
    }

    public PodmanComputer(Role role, String uuid) {
        super(role, uuid);
        this.containerName = "agentsoftware-" + roleId();
    }

    @Override
    public void powerOn() {
        if (isOn()) {
            return;
        }
        if (!exists()) {
            createContainer();
        }
        pod(null, "start", containerName);
        ison = true;
    }

    @Override
    public void powerOff() {
        if (!exists()) {
            ison = false;
            return;
        }
        pod(null, "stop", containerName);
        ison = false;
    }

    @Override
    public void destroy() {
        pod(null, "rm", "-f", containerName);
        ison = false;
    }

    @Override
    public String runCommand(String command, int timeout, int maxChars) {
        return pod(null, "exec", containerName, "bash", "-lc", command);
    }

    @Override
    public String readFile(String path) {
        return pod(null, "exec", containerName, "cat", path);
    }

    @Override
    public void writeFile(String path, String content) {
        pod(content, "exec", "-i", containerName, "tee", path);
    }

    @Override
    public String listDir(String path) {
        return pod(null, "exec", containerName, "ls", "-la", path);
    }

    @Override
    public void deleteFile(String path) {
        pod(null, "exec", containerName, "rm", "-f", path);
    }

    private boolean exists() {
        Exec r = exec(null, 30, "podman", "container", "exists", containerName);
        return r.exit == 0;
    }

    private void createContainer() {
        List<String> cmd = new ArrayList<>(List.of("podman", "run", "-d", "--name", containerName));
        cmd.add("-v");
        cmd.add(hostDir() + ":/home/agent");
        cmd.add("-v");
        cmd.add(driveDir() + ":/mnt/drive");
        cmd.add(defaultImage());
        cmd.add("sleep");
        cmd.add("infinity");
        exec(null, 300, cmd.toArray(new String[0]));
    }

    private String pod(String stdin, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("podman");
        cmd.addAll(List.of(args));
        Exec r = exec(stdin, 120, cmd.toArray(new String[0]));
        return r.output;
    }

    private static final class Exec {
        int exit;
        String output = "";
    }

    private Exec exec(String stdin, int timeoutSeconds, String... cmd) {
        Exec result = new Exec();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process p = pb.start();
            if (stdin != null) {
                try (OutputStream os = p.getOutputStream()) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                p.getOutputStream().close();
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                result.output = "[timeout] " + String.join(" ", cmd);
                result.exit = -1;
                return result;
            }
            result.exit = p.exitValue();
            result.output = out + (err.isBlank() ? "" : "\n[stderr] " + err);
        } catch (IOException e) {
            result.output = "[error] " + e.getMessage();
            result.exit = -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.output = "[interrupted]";
            result.exit = -1;
        }
        return result;
    }
}
