package com.agent.software.tool.computer;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.RoleId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.agent.software.tool.computer.Shell.CommandResult;

/**
 * 本地目录形态的个人电脑：直接以宿主文件系统为工作目录，命令经子进程执行。
 *
 * <p><b>目录布局</b>（对齐 master {@code ComputerManager}，基准由 {@code ./data} 改为
 * {@link AppPaths#dataDir()}）：角色目录 {@code <base_dir>/<roleId>}
 * （默认 {@code dataDir()/computers/<roleId>}），共享云盘 {@code <drive_dir>}
 * （默认 {@code dataDir()/drive}）。本地电脑构造即视为已开机（对齐 master
 * {@code LocalComputer} 的 {@code on = true}），{@code powerOn()} 只做幂等的目录补齐。
 *
 * <p><b>ComputerSpec.options 键</b>（缺省即用默认值，未知键忽略）：
 * <ul>
 *   <li>{@code base_dir}：本地电脑根目录，默认 {@code dataDir()/computers}；相对路径相对 {@code dataDir()} 解析</li>
 *   <li>{@code drive_dir}：共享云盘根目录，默认 {@code dataDir()/drive}；相对路径相对 {@code dataDir()} 解析</li>
 *   <li>{@code workdir}：覆盖工作目录，默认即角色目录</li>
 *   <li>{@code username} / {@code uid} / {@code name}：仅用于状态描述</li>
 *   <li>{@code workdir} 之外的所有路径都会被 {@link ShellSupport#localPath} 限制在 {@code workdir()} 之内</li>
 * </ul>
 */
public final class LocalShell implements Shell {

    private final RoleId roleId;
    private final Path workdir;
    private final Path driveRoot;
    private final String username;
    private final String displayName;

    /** 本地目录形态没有可"关"的实体，只维护逻辑开关（{@code run} 据此拒绝执行）。 */
    private volatile boolean on = true;

    /** 记录角色、目录规格与路径解析器。 */
    public LocalShell(RoleId roleId, RoleSpec.ComputerSpec spec, AppPaths paths) {
        this.roleId = roleId;
        Map<String, String> options = spec == null || spec.options() == null ? Map.of() : spec.options();
        Path dataDir = paths == null ? Paths.get("data") : paths.dataDir();
        Path baseDir = resolve(dataDir, ShellSupport.option(options, "base_dir", "computers"));
        String wd = ShellSupport.option(options, "workdir", "");
        this.workdir = (wd.isEmpty() ? baseDir.resolve(roleId.value()) : resolve(baseDir, wd))
                .toAbsolutePath().normalize();
        this.driveRoot = resolve(dataDir, ShellSupport.option(options, "drive_dir", "drive"))
                .toAbsolutePath().normalize();
        this.username = ShellSupport.option(options, "username", "agent");
        this.displayName = ShellSupport.option(options, "name", roleId.value());
        try {
            Files.createDirectories(this.workdir);
            Files.createDirectories(this.driveRoot);
        } catch (IOException e) {
            throw new DomainError("shell.local.mkdir.failed", "无法创建本地电脑目录: " + this.workdir, e);
        }
    }

    /** 相对路径相对 {@code dataDir()} 解析，绝对路径原样使用。 */
    private static Path resolve(Path base, String value) {
        Path candidate = Paths.get(value);
        return candidate.isAbsolute() ? candidate : base.resolve(candidate);
    }

    @Override
    public String powerOn() {
        on = true;
        try {
            Files.createDirectories(workdir);
            Files.createDirectories(driveRoot);
        } catch (IOException e) {
            return "本地电脑 [" + roleId.value() + "] 开机失败: " + e.getMessage();
        }
        return describe();
    }

    @Override
    public String powerOff() {
        on = false;
        return "本地电脑 [" + roleId.value() + "] 已关机（本地目录形态，无进程需要停止）。";
    }

    @Override
    public boolean poweredOn() {
        return on;
    }

    @Override
    public CommandResult run(String command, Duration timeout, int maxOutputChars) {
        if (!on) {
            return new CommandResult(-1, "", "电脑未开机，请先开机。");
        }
        if (command == null || command.isBlank()) {
            return new CommandResult(-1, "", "命令为空。");
        }
        // Windows 宿主用 cmd，其余用 POSIX sh；工作目录固定为 workdir()，让相对路径与
        // readFile/writeFile/listDir 的解析基准一致（对齐 Shell 抽象里的"电脑工作目录"语义）。
        List<String> cmd = ShellSupport.isWindows()
                ? List.of("cmd", "/c", command)
                : List.of("sh", "-c", command);
        return ShellSupport.exec(cmd, null, timeout, maxOutputChars, workdir);
    }

    @Override
    public String readFile(String path) {
        Path target = Path.of(resolvePath(path));
        if (!Files.isRegularFile(target)) {
            throw new DomainError("shell.read.failed", "文件不存在或不是普通文件: " + target);
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DomainError("shell.read.failed", "读取失败: " + target, e);
        }
    }

    @Override
    public void writeFile(String path, String content) {
        Path target = Path.of(resolvePath(path));
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DomainError("shell.write.failed", "写入失败: " + target, e);
        }
    }

    @Override
    public List<String> listDir(String path) {
        Path target = Path.of(resolvePath(path));
        if (!Files.isDirectory(target)) {
            throw new DomainError("shell.list.failed", "目录不存在: " + target);
        }
        try (var stream = Files.list(target)) {
            List<String> names = new ArrayList<>();
            stream.forEach(p -> names.add(p.getFileName().toString()));
            names.sort(String::compareTo);
            return names;
        } catch (IOException e) {
            throw new DomainError("shell.list.failed", "列目录失败: " + target, e);
        }
    }

    @Override
    public void deleteFile(String path) {
        Path target = Path.of(resolvePath(path));
        try {
            // 对齐远端 rm -f 语义：目标不存在不算失败
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new DomainError("shell.delete.failed", "删除失败: " + target, e);
        }
    }

    /** 解析到工作目录之内的绝对路径；越界（含绝对路径指向别处）抛 {@code shell.path.escape}。 */
    private String resolvePath(String path) {
        return ShellSupport.localPath(workdir.toString(), path);
    }

    @Override
    public String workdir() {
        return workdir.toString();
    }

    @Override
    public String driveRoot() {
        return driveRoot.toString();
    }

    @Override
    public String hostDir() {
        return workdir.toString();
    }

    @Override
    public String describe() {
        return "电脑 [" + roleId.value() + "]（本地目录形态" + (displayName.isEmpty() ? "" : "，" + displayName)
                + "，用户 " + username + "）：状态=" + (on ? "已开机" : "已关机")
                + "，工作目录=" + workdir + "，共享云盘=" + driveRoot;
    }
}
