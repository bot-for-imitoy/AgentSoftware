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
        // 旧容器可能是按"每角色一个云盘目录 / 没有个人用户"的老逻辑建的：挂载改不了，只能重建。
        if (exists() && mountsAreStale()) {
            logger.info("PodmanComputer[{}] container {} has stale mounts; recreating it",
                    roleId(), containerName);
            destroy();
        }
        if (!exists()) {
            createContainer();
        }
        Exec r = exec(null, 120, "podman", "start", containerName);
        ison = r.exit == 0;
        if (!ison) {
            logger.warn("PodmanComputer[{}] powerOn failed: {}", roleId(), r.output);
            return;
        }
        // 每次上电都幂等地补齐：容器内用户（含免密 sudo）、个人主目录、云盘目录
        setupUserAndDrive();
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
        return pod(null, "exec", "--user", username(), containerName, "bash", "-lc", command);
    }

    @Override
    public String readFile(String path) {
        return pod(null, "exec", "--user", username(), containerName, "cat", path);
    }

    @Override
    public void writeFile(String path, String content) {
        pod(content, "exec", "-i", "--user", username(), containerName, "tee", path);
    }

    @Override
    public String listDir(String path) {
        return pod(null, "exec", "--user", username(), containerName, "ls", "-la", path);
    }

    @Override
    public void deleteFile(String path) {
        pod(null, "exec", "--user", username(), containerName, "rm", "-f", path);
    }

    private boolean exists() {
        Exec r = exec(null, 30, "podman", "container", "exists", containerName);
        return r.exit == 0;
    }

    private boolean imageExists() {
        Exec r = exec(null, 30, "podman", "image", "exists", defaultImage());
        return r.exit == 0;
    }

    /** 基础镜像不存在时，用项目根目录的 Containerfile 构建（master 的既有行为）。 */
    private void buildImage() {
        String containerfile = containerfile();
        logger.info("PodmanComputer[{}] base image {} missing; building from {} (this may take a while)",
                roleId(), defaultImage(), containerfile);
        Exec r = exec(null, 900, "podman", "build", "-t", defaultImage(), "-f", containerfile, ".");
        if (r.exit != 0) {
            logger.warn("PodmanComputer[{}] image build failed: {}", roleId(), r.output);
        }
    }

    private void createContainer() {
        try {
            java.nio.file.Files.createDirectories(hostDir());
            java.nio.file.Files.createDirectories(driveRoot());
        } catch (java.io.IOException e) {
            logger.warn("PodmanComputer[{}] cannot create host dirs", roleId(), e);
        }
        if (!imageExists()) {
            buildImage();
        }
        List<String> cmd = new ArrayList<>(List.of("podman", "run", "-d", "--name", containerName));
        cmd.add("-v");
        cmd.add(hostDir() + ":" + workdir());            // 个人电脑目录 → 自己的主目录
        cmd.add("-v");
        cmd.add(driveRoot() + ":" + DRIVE_MOUNT);        // 全公司共享云盘 → /mnt/drive
        cmd.add(defaultImage());
        cmd.add("sleep");
        cmd.add("infinity");
        Exec r = exec(null, 300, cmd.toArray(new String[0]));
        if (r.exit != 0) {
            logger.warn("PodmanComputer[{}] podman run failed: {}", roleId(), r.output);
        }
    }

    /**
     * 幂等地在容器内建号并初始化云盘，每次上电都跑一遍：
     *
     * <ul>
     *   <li>建用户 {@code <username>}（固定 uid、加入 sudo 组）并建好个人主目录；</li>
     *   <li>写 {@code /etc/sudoers.d/<username>}，免密 sudo；</li>
     *   <li>建 {@code /mnt/drive/Public}（777）与 {@code /mnt/drive/<username>}（归属本人）。</li>
     * </ul>
     */
    private void setupUserAndDrive() {
        String personal = DRIVE_MOUNT + "/" + driveDirName();
        Exec r = exec(null, 120, "podman", "exec", containerName, "sh", "-c",
                userSetupScript(username(), uid(), workdir()));
        if (r.exit != 0) {
            logger.warn("PodmanComputer[{}] in-container user setup failed: {}", roleId(), r.output);
        }
        r = exec(null, 120, "podman", "exec", containerName, "sh", "-c",
                driveSetupScript(personal, uid(), "CEO".equalsIgnoreCase(roleId())));
        if (r.exit != 0) {
            logger.warn("PodmanComputer[{}] cloud drive setup failed: {}", roleId(), r.output);
        } else {
            logger.info("PodmanComputer[{}] ready: user={} uid={} home={} drive={}",
                    roleId(), username(), uid(), workdir(), personal);
        }
    }

    /**
     * 容器内建号脚本（幂等）：建 sudo 组 → 建用户（固定 uid）→ 写免密 sudoers → 建主目录并 chown。
     *
     * <p>抽成静态纯函数是为了能在没有 podman 的环境里断言脚本内容。
     */
    static String userSetupScript(String user, int uid, String home) {
        return "getent group sudo >/dev/null || groupadd sudo; "
                + "id -u " + sh(user) + " >/dev/null 2>&1 || useradd -s /bin/bash -u " + uid
                + " -G sudo " + sh(user) + "; "
                + "mkdir -p /etc/sudoers.d; "
                + "echo " + sh(user + " ALL=(ALL) NOPASSWD:ALL") + " > /etc/sudoers.d/" + sh(user) + "; "
                + "chmod 440 /etc/sudoers.d/" + sh(user) + "; "
                + "mkdir -p " + sh(home) + "; chown -R " + uid + ":" + uid + " " + sh(home);
    }

    /**
     * 云盘初始化脚本（幂等）：建 {@code Public}（777）和本人目录（755、归属本人）；
     * CEO 额外接管 {@code Public} 的所有权（对齐 master）。
     */
    static String driveSetupScript(String personal, int uid, boolean ceo) {
        String script = "mkdir -p " + DRIVE_MOUNT + "/Public " + sh(personal) + "; "
                + "chmod 777 " + DRIVE_MOUNT + "/Public; chmod 755 " + sh(personal) + "; "
                + "chown " + uid + ":" + uid + " " + sh(personal);
        if (ceo) {
            script += "; chown " + uid + ":" + uid + " " + DRIVE_MOUNT + "/Public";
        }
        return script;
    }

    /**
     * 已存在的容器挂的目录是否还是老逻辑那套。查不到（inspect 失败）时返回 false：
     * 宁可少重建一次，也不要因为一次瞬时失败就把容器删掉。
     */
    private boolean mountsAreStale() {
        Exec r = exec(null, 30, "podman", "inspect", containerName, "-f", "{{json .Mounts}}");
        if (r.exit != 0) {
            return false;
        }
        return !(r.output.contains(driveRoot().toString()) && r.output.contains(workdir()));
    }

    /** 单引号包裹，供容器内的 sh 解析（路径/内容里的单引号做转义）。 */
    private static String sh(String s) {
        return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'";
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
