package com.agent.software.tool.computer;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.RoleId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.agent.software.tool.computer.Shell.CommandResult;

/**
 * Podman 容器形态的个人电脑：每角色一个容器，命令经 podman exec 执行。
 *
 * <p><b>命名与目录布局</b>（对齐 master {@code ComputerManager} / {@code PodmanComputer}）：
 * 容器名默认 {@code maf-<roleId>}，镜像默认 {@code maf-base:latest}，网络默认
 * {@link ShellRegistry#DEFAULT_NETWORK}（{@code maf-net}）。宿主机目录
 * {@code dataDir()/computers/<roleId>} 挂到容器内工作目录 {@code /home/<username>}，
 * 共享云盘 {@code dataDir()/drive} 挂到 {@code /mnt/drive}，npm 缓存
 * {@code dataDir()/computers/.npm-cache} 挂到 {@code /root/.npm}。容器内以
 * {@code sleep infinity} 保活，{@code powerOn()} 负责建目录 / 建容器 / 起容器 /
 * 幂等地建用户与云盘目录。
 *
 * <p><b>ComputerSpec.options 键</b>（缺省即用默认值，未知键忽略）：
 * <ul>
 *   <li>{@code container}（别名 {@code container_name}）：容器名，默认 {@code maf-<roleId>}</li>
 *   <li>{@code image}：镜像，默认 {@code maf-base:latest}</li>
 *   <li>{@code network}：容器网络，默认构造期注入的网络名</li>
 *   <li>{@code username}：容器内用户名，默认 {@code agent}（同时决定云盘个人目录名）</li>
 *   <li>{@code uid}：容器内 uid，默认 {@code 1100}</li>
 *   <li>{@code workdir}：容器内工作目录，默认 {@code /home/<username>}</li>
 *   <li>{@code name}：仅用于状态描述</li>
 * </ul>
 *
 * <p><b>文件操作实现选择</b>：读文件用 {@code base64}（GNU/busybox 都支持），避免二进制内容与
 * 换行被管道改写；写文件用 {@code cat > "$1"} 从 stdin 灌入并经 argv 传路径，路径不被 shell 解析；
 * 列目录用 {@code ls -1A}（含隐藏文件、去掉 . 与 ..），比解析 {@code ls -la} 的列宽更稳。
 * 没有采用 {@code podman cp}：它需要先落到宿主机临时文件，路径映射与权限（--user）都要额外处理，
 * 且会绕过 {@code workdir()} 语义。
 */
public final class PodmanShell implements Shell {

    private static final Logger logger = LoggerFactory.getLogger(PodmanShell.class);

    /** 默认镜像（对齐 master {@code Computer.DEFAULT_IMAGE}）。 */
    public static final String DEFAULT_IMAGE = "maf-base:latest";

    /** 容器内共享云盘挂载点（对齐 master）。 */
    public static final String DRIVE_MOUNT = "/mnt/drive";

    /** podman 子命令超时：容器操作通常亚秒级，卡住应快速失败而不是静默等待。 */
    private static final Duration POD_TIMEOUT = Duration.ofSeconds(60);

    private static final int POD_MAX_OUTPUT = 2_000;

    private final RoleId roleId;
    private final String image;
    private final String containerName;
    private final String username;
    private final int uid;
    private final String displayName;
    private final String workdir;
    private final String networkName;
    private final Path hostDir;
    private final Path driveHostDir;
    private final Path npmCacheHostDir;

    private volatile boolean on;

    /** 记录角色、容器规格、路径解析器与共享网络名。 */
    public PodmanShell(RoleId roleId, RoleSpec.ComputerSpec spec, AppPaths paths, String networkName) {
        this.roleId = roleId;
        Map<String, String> options = spec == null || spec.options() == null ? Map.of() : spec.options();
        this.image = ShellSupport.option(options, "image", DEFAULT_IMAGE);
        String container = ShellSupport.firstOption(options, "", "container", "container_name");
        this.containerName = container.isEmpty() ? "maf-" + roleId.value() : container;
        this.username = ShellSupport.option(options, "username", "agent");
        this.uid = ShellSupport.intOption(options, "uid", 1100);
        this.displayName = ShellSupport.option(options, "name", roleId.value());
        this.workdir = ShellSupport.option(options, "workdir", "/home/" + this.username);
        String fallbackNetwork = (networkName == null || networkName.isBlank())
                ? ShellRegistry.DEFAULT_NETWORK : networkName.trim();
        this.networkName = ShellSupport.option(options, "network", fallbackNetwork);

        Path dataDir = paths == null ? Paths.get("data") : paths.dataDir();
        this.hostDir = dataDir.resolve("computers").resolve(roleId.value()).toAbsolutePath().normalize();
        this.driveHostDir = dataDir.resolve("drive").toAbsolutePath().normalize();
        this.npmCacheHostDir = dataDir.resolve("computers").resolve(".npm-cache").toAbsolutePath().normalize();
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    @Override
    public String powerOn() {
        if (on) {
            return describe();
        }
        try {
            ensureContainer();
            on = true;
            return describe();
        } catch (RuntimeException e) {
            logger.warn("电脑 [{}] podman 开机失败: {}", roleId.value(), e.getMessage());
            return "电脑 [" + roleId.value() + "]（podman 容器 " + containerName + "）开机失败: " + e.getMessage();
        }
    }

    @Override
    public String powerOff() {
        if (on && ShellSupport.executableOnPath("podman")) {
            CommandResult r = pod("stop", containerName);
            if (r.exitCode() != 0) {
                logger.warn("电脑 [{}] podman stop 返回 {}: {}", roleId.value(), r.exitCode(), r.combined().strip());
            }
        }
        on = false;
        return "电脑 [" + roleId.value() + "]（podman 容器 " + containerName + "）已关机。";
    }

    @Override
    public boolean poweredOn() {
        return on;
    }

    /** 建目录 → 建/起容器 → 幂等初始化用户与云盘目录。 */
    private void ensureContainer() {
        createHostDirs();
        if (!ShellSupport.executableOnPath("podman")) {
            throw new DomainError("shell.podman.missing",
                    "宿主机未安装 podman，无法创建容器 " + containerName + "（如需本地模拟请把 kind 设为 local）");
        }
        if (!existingContainers().contains(containerName)) {
            createContainer();
        }
        if (!containerRunning()) {
            CommandResult started = pod("start", containerName);
            if (started.exitCode() != 0) {
                throw new DomainError("shell.podman.start.failed", "podman start 失败（" + started.exitCode()
                        + "）: " + started.combined().strip());
            }
        }
        initialiseUserAndDrive();
    }

    private void createHostDirs() {
        try {
            Files.createDirectories(hostDir);
            Files.createDirectories(driveHostDir);
            Files.createDirectories(npmCacheHostDir);
        } catch (IOException e) {
            throw new DomainError("shell.podman.mkdir.failed", "无法创建宿主机映射目录: " + hostDir, e);
        }
    }

    /** {@code podman ps -a} 的全部容器名。 */
    private Set<String> existingContainers() {
        CommandResult r = pod("ps", "-a", "--format", "{{.Names}}");
        if (r.exitCode() != 0) {
            throw new DomainError("shell.podman.ps.failed",
                    "podman ps 失败（" + r.exitCode() + "）: " + r.combined().strip());
        }
        Set<String> names = new HashSet<>();
        for (String line : r.stdout().split("\n")) {
            if (!line.isBlank()) {
                names.add(line.strip());
            }
        }
        return names;
    }

    private boolean containerRunning() {
        CommandResult r = pod("ps", "--filter", "name=" + containerName, "--format", "{{.Names}}");
        return r.exitCode() == 0 && r.stdout().contains(containerName);
    }

    /** 创建容器（失败时清理残留后重试，最多 3 次，对齐 master）。 */
    private void createContainer() {
        CommandResult r = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            r = pod("run", "-d", "--name", containerName, "--network", networkName,
                    "-v", hostDir + ":" + workdir,
                    "-v", driveHostDir + ":" + DRIVE_MOUNT,
                    "-v", npmCacheHostDir + ":/root/.npm",
                    image, "sleep", "infinity");
            if (r.exitCode() == 0) {
                logger.info("电脑 [{}] podman 容器已创建: {}（镜像 {}，网络 {}）",
                        roleId.value(), containerName, image, networkName);
                return;
            }
            logger.warn("电脑 [{}] podman run 第 {} 次失败（{}）: {}，清理残留后重试",
                    roleId.value(), attempt, r.exitCode(), r.combined().strip());
            pod("rm", "-f", containerName);
            sleepOneSecond();
        }
        throw new DomainError("shell.podman.run.failed", "podman run 创建容器失败（"
                + (r == null ? -1 : r.exitCode()) + "）: " + (r == null ? "" : r.combined().strip()));
    }

    /** 幂等创建容器内用户（含 sudo 免密）与云盘个人目录；所有权归属容器内 uid。 */
    private void initialiseUserAndDrive() {
        String user = ShellSupport.quote(username);
        String wd = ShellSupport.quote(workdir);
        String sudoers = ShellSupport.quote(username + " ALL=(ALL) NOPASSWD:ALL");
        String setup = "id -u " + user + " >/dev/null 2>&1 || useradd -s /bin/bash -u " + uid + " -G sudo " + user + "; "
                + "[ -f /etc/sudoers.d/" + user + " ] || echo " + sudoers + " > /etc/sudoers.d/" + user + "; "
                + "mkdir -p " + wd + "; chown -R " + uid + ":" + uid + " " + wd;
        CommandResult r = pod("exec", containerName, "sh", "-c", setup);
        if (r.exitCode() != 0) {
            throw new DomainError("shell.podman.user.failed", "容器内用户初始化失败（" + r.exitCode()
                    + "）: " + r.combined().strip());
        }

        String personal = ShellSupport.quote(DRIVE_MOUNT + "/" + driveDirName());
        String driveInit = "mkdir -p " + DRIVE_MOUNT + "/Public " + personal + "; "
                + "chmod 777 " + DRIVE_MOUNT + "/Public; chmod 755 " + personal + "; "
                + "chown " + uid + ":" + uid + " " + personal;
        if ("CEO".equalsIgnoreCase(roleId.value())) {
            driveInit += "; chown " + uid + ":" + uid + " " + DRIVE_MOUNT + "/Public";
        }
        r = pod("exec", containerName, "sh", "-c", driveInit);
        if (r.exitCode() != 0) {
            throw new DomainError("shell.podman.drive.failed", "云盘目录初始化失败（" + r.exitCode()
                    + "）: " + r.combined().strip());
        }
    }

    /** 云盘个人目录名：容器内用户名（对齐 master {@code driveDirName()}）。 */
    private String driveDirName() {
        return username.isEmpty() ? displayName : username;
    }

    // ── 命令与文件 ──────────────────────────────────────────────────────────

    @Override
    public CommandResult run(String command, Duration timeout, int maxOutputChars) {
        if (!on) {
            return new CommandResult(-1, "", "电脑未开机，请先开机。");
        }
        if (command == null || command.isBlank()) {
            return new CommandResult(-1, "", "命令为空。");
        }
        // command 作为独立 argv 传给 sh -c，不再经过外层 shell 解析
        return ShellSupport.exec(
                List.of("podman", "exec", "--user", username, containerName, "sh", "-c", command),
                null, timeout, maxOutputChars);
    }

    @Override
    public String readFile(String path) {
        requireOn();
        String target = ShellSupport.containerPath(workdir, path);
        CommandResult r = execContainer("base64 -- \"$1\"", target);
        if (r.exitCode() == 0) {
            try {
                return new String(Base64.getMimeDecoder().decode(r.stdout()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // 输出不是合法 base64（极小概率），按原文返回
                return r.stdout();
            }
        }
        // 回退：镜像里没有 base64 时退回 cat
        r = execContainer("cat -- \"$1\"", target);
        if (r.exitCode() != 0) {
            throw new DomainError("shell.read.failed",
                    "读取失败（" + r.exitCode() + "）: " + target + " - " + r.combined().strip());
        }
        return r.stdout();
    }

    @Override
    public void writeFile(String path, String content) {
        requireOn();
        String target = ShellSupport.containerPath(workdir, path);
        String parent = ShellSupport.parentOf(target);
        CommandResult r = ShellSupport.exec(
                List.of("podman", "exec", "-i", "--user", username, containerName,
                        "sh", "-c", "mkdir -p -- \"$2\" && cat > \"$1\"", "sh", target, parent),
                content == null ? "" : content, ShellSupport.DEFAULT_TIMEOUT, POD_MAX_OUTPUT);
        if (r.exitCode() != 0) {
            throw new DomainError("shell.write.failed",
                    "写入失败（" + r.exitCode() + "）: " + target + " - " + r.combined().strip());
        }
    }

    @Override
    public List<String> listDir(String path) {
        requireOn();
        String target = ShellSupport.containerPath(workdir, path);
        CommandResult r = execContainer("ls -1A -- \"$1\"", target);
        if (r.exitCode() != 0) {
            throw new DomainError("shell.list.failed",
                    "列目录失败（" + r.exitCode() + "）: " + target + " - " + r.combined().strip());
        }
        return splitLines(r.stdout());
    }

    @Override
    public void deleteFile(String path) {
        requireOn();
        String target = ShellSupport.containerPath(workdir, path);
        CommandResult r = execContainer("rm -f -- \"$1\"", target);
        if (r.exitCode() != 0) {
            throw new DomainError("shell.delete.failed",
                    "删除失败（" + r.exitCode() + "）: " + target + " - " + r.combined().strip());
        }
    }

    @Override
    public String workdir() {
        return workdir;
    }

    @Override
    public String driveRoot() {
        return DRIVE_MOUNT;
    }

    @Override
    public String hostDir() {
        return hostDir.toString();
    }

    @Override
    public String describe() {
        return "电脑 [" + roleId.value() + "]（podman 容器 " + containerName
                + (displayName.isEmpty() || displayName.equals(roleId.value()) ? "" : "，" + displayName)
                + "，镜像 " + image + "，网络 " + networkName + "）：状态=" + (on ? "已开机" : "已关机")
                + "，工作目录=" + workdir + "，宿主机映射=" + hostDir;
    }

    // ── 内部工具 ────────────────────────────────────────────────────────────

    /** 经 argv 传路径执行容器内脚本，避免路径被 shell 二次解析。 */
    private CommandResult execContainer(String script, String argument) {
        return ShellSupport.exec(
                List.of("podman", "exec", "--user", username, containerName, "sh", "-c", script, "sh", argument),
                null, ShellSupport.DEFAULT_TIMEOUT, POD_MAX_OUTPUT);
    }

    private CommandResult pod(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("podman");
        cmd.addAll(List.of(args));
        return ShellSupport.exec(cmd, null, POD_TIMEOUT, POD_MAX_OUTPUT);
    }

    private void requireOn() {
        if (!on) {
            throw new DomainError("shell.powered-off", "电脑未开机: " + roleId.value());
        }
    }

    private static List<String> splitLines(String text) {
        List<String> names = new ArrayList<>();
        for (String line : text.split("\n")) {
            String value = line.strip();
            if (!value.isEmpty()) {
                names.add(value);
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    private static void sleepOneSecond() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
