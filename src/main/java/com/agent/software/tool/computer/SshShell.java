package com.agent.software.tool.computer;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.RoleId;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import com.agent.software.tool.computer.Shell.CommandResult;

/**
 * SSH 远程主机形态的个人电脑：命令与文件操作都经 SSH 通道完成。
 *
 * <p>不引入 sshj / jsch：直接调用系统 {@code ssh}，与 master {@code SSHComputer} 一致，
 * 也复用宿主既有的 known_hosts / agent 配置。命令形如
 * {@code ssh -o BatchMode=yes -o StrictHostKeyChecking=no -o ConnectTimeout=10 [-p port] [-i key] user@host <cmd>}；
 * {@code BatchMode=yes} 保证缺密钥时快速失败而不是阻塞等密码输入。
 *
 * <p>远端工作目录默认 {@code ~/maf-<roleId>}（对齐 master），命令执行前先
 * {@code mkdir -p <workdir> && cd <workdir>}；文件操作读用 {@code base64}（避免二进制/换行问题），
 * 写用 {@code cat >} 从 stdin 灌入，路径经单引号引用后交给远端 shell。
 *
 * <p><b>ComputerSpec.options 键</b>（缺省即用默认值，未知键忽略）：
 * <ul>
 *   <li>{@code host}：远端主机，<b>必填</b>，缺失时构造期抛 {@code shell.ssh.host.missing}</li>
 *   <li>{@code port}：SSH 端口，默认 {@code 22}</li>
 *   <li>{@code user}：登录用户，缺省时用 ssh 默认用户</li>
 *   <li>{@code key_path}（别名 {@code key}）：私钥文件路径</li>
 *   <li>{@code workdir}：远端工作目录，默认 {@code ~/maf-<roleId>}</li>
 *   <li>{@code name}：仅用于状态描述</li>
 * </ul>
 */
public final class SshShell implements Shell {

    /** SSH 既无"电源"也无宿主机目录映射：hostDir() 返回空串，workdir() 以 ~ 表达。 */
    private static final String DRIVE_ROOT = "/mnt/drive";

    private final RoleId roleId;
    private final String host;
    private final String user;
    private final String keyPath;
    private final int port;
    private final String workdir;
    private final String displayName;

    private volatile boolean on;

    /** 记录角色与 SSH 连接规格。 */
    public SshShell(RoleId roleId, RoleSpec.ComputerSpec spec) {
        this.roleId = roleId;
        Map<String, String> options = spec == null || spec.options() == null ? Map.of() : spec.options();
        this.host = ShellSupport.option(options, "host", "");
        if (host.isEmpty()) {
            throw new DomainError("shell.ssh.host.missing", "SSH 电脑需要 host 参数（远端主机地址）：" + roleId.value());
        }
        this.user = ShellSupport.option(options, "user", "");
        this.keyPath = ShellSupport.firstOption(options, "", "key_path", "key");
        this.port = ShellSupport.intOption(options, "port", 22);
        this.workdir = ShellSupport.option(options, "workdir", "~/maf-" + roleId.value());
        this.displayName = ShellSupport.option(options, "name", roleId.value());
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    @Override
    public String powerOn() {
        if (on) {
            // 幂等：已连接就直接返回现有状态描述，不重复建连
            return describe();
        }
        CommandResult r = ssh("echo ok", Duration.ofSeconds(60), 2_000);
        if (r.ok() && r.combined().contains("ok")) {
            on = true;
            return "电脑 [" + roleId.value() + "]（ssh " + host + "）已连接，工作目录: " + workdir;
        }
        on = false;
        return "电脑 [" + roleId.value() + "] 连接失败（ssh " + host + "）: " + r.combined().strip();
    }

    @Override
    public String powerOff() {
        on = false;
        return "电脑 [" + roleId.value() + "]（ssh " + host + "）已断开。";
    }

    @Override
    public boolean poweredOn() {
        return on;
    }

    // ── 命令与文件 ──────────────────────────────────────────────────────────

    @Override
    public CommandResult run(String command, Duration timeout, int maxOutputChars) {
        if (!on) {
            return new CommandResult(-1, "", "电脑未连接，请先开机。");
        }
        if (command == null || command.isBlank()) {
            return new CommandResult(-1, "", "命令为空。");
        }
        return ssh(command, timeout, maxOutputChars);
    }

    @Override
    public String readFile(String path) {
        requireOn();
        String target = ShellSupport.quote(ShellSupport.remoteRelativePath(path));
        CommandResult r = ssh("base64 -- " + target, ShellSupport.DEFAULT_TIMEOUT, 0);
        if (r.ok()) {
            try {
                return new String(Base64.getMimeDecoder().decode(r.stdout()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return r.stdout();
            }
        }
        r = ssh("cat -- " + target, ShellSupport.DEFAULT_TIMEOUT, 0);
        if (!r.ok()) {
            throw new DomainError("shell.read.failed",
                    "读取失败（" + r.exitCode() + "）: " + path + " - " + r.combined().strip());
        }
        return r.stdout();
    }

    @Override
    public void writeFile(String path, String content) {
        requireOn();
        String target = ShellSupport.remoteRelativePath(path);
        String parent = ShellSupport.parentOf(target);
        CommandResult r = sshInWorkdir(
                "mkdir -p -- " + ShellSupport.quote(parent) + " && cat > " + ShellSupport.quote(target),
                content == null ? "" : content, ShellSupport.DEFAULT_TIMEOUT, 2_000);
        if (!r.ok()) {
            throw new DomainError("shell.write.failed",
                    "写入失败（" + r.exitCode() + "）: " + path + " - " + r.combined().strip());
        }
    }

    @Override
    public List<String> listDir(String path) {
        requireOn();
        String target = ShellSupport.quote(ShellSupport.remoteRelativePath(path));
        CommandResult r = ssh("ls -1A -- " + target, ShellSupport.DEFAULT_TIMEOUT, 20_000);
        if (!r.ok()) {
            throw new DomainError("shell.list.failed",
                    "列目录失败（" + r.exitCode() + "）: " + path + " - " + r.combined().strip());
        }
        List<String> names = new ArrayList<>();
        for (String line : r.stdout().split("\n")) {
            String value = line.strip();
            if (!value.isEmpty()) {
                names.add(value);
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    @Override
    public void deleteFile(String path) {
        requireOn();
        String target = ShellSupport.quote(ShellSupport.remoteRelativePath(path));
        CommandResult r = ssh("rm -f -- " + target, ShellSupport.DEFAULT_TIMEOUT, 2_000);
        if (!r.ok()) {
            throw new DomainError("shell.delete.failed",
                    "删除失败（" + r.exitCode() + "）: " + path + " - " + r.combined().strip());
        }
    }

    @Override
    public String workdir() {
        return workdir;
    }

    @Override
    public String driveRoot() {
        return DRIVE_ROOT;
    }

    @Override
    public String hostDir() {
        // 远程主机没有宿主机映射目录
        return "";
    }

    @Override
    public String describe() {
        return "电脑 [" + roleId.value() + "]（ssh " + (user.isEmpty() ? "" : user + "@") + host
                + ":" + port + (displayName.isEmpty() || displayName.equals(roleId.value()) ? "" : "，" + displayName)
                + "）：状态=" + (on ? "已连接" : "未连接") + "，工作目录=" + workdir;
    }

    // ── 内部工具 ────────────────────────────────────────────────────────────

    /** 合成远端命令：建工作目录后 cd 进去，再执行用户命令（对齐 master 的 {@code sshBase}）。 */
    private CommandResult ssh(String remoteCommand, Duration timeout, int maxOutputChars) {
        return sshInWorkdir(remoteCommand, null, timeout, maxOutputChars);
    }

    /** 同 {@link #ssh}，但支持从 stdin 灌入内容（写文件用），并保留 cd 到工作目录的前缀。 */
    private CommandResult sshInWorkdir(String remoteCommand, String stdinInput,
                                       Duration timeout, int maxOutputChars) {
        String wd = ShellSupport.remoteWorkdirWord(workdir);
        String script = "mkdir -p " + wd + " && cd " + wd + " && " + remoteCommand;
        return sshExec(script, stdinInput, timeout, maxOutputChars);
    }

    private CommandResult sshExec(String remoteCommand, String stdinInput,
                                  Duration timeout, int maxOutputChars) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        cmd.add("-o");
        cmd.add("BatchMode=yes");
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o");
        cmd.add("ConnectTimeout=10");
        if (port > 0) {
            cmd.add("-p");
            cmd.add(String.valueOf(port));
        }
        if (!keyPath.isEmpty()) {
            cmd.add("-i");
            cmd.add(keyPath);
        }
        cmd.add(user.isEmpty() ? host : user + "@" + host);
        cmd.add(remoteCommand);
        return ShellSupport.exec(cmd, stdinInput, timeout, maxOutputChars);
    }

    private void requireOn() {
        if (!on) {
            throw new DomainError("shell.powered-off", "电脑未连接: " + roleId.value());
        }
    }
}
