package com.agent.software.tool.computer;

import com.agent.software.kernel.DomainError;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 三种 Shell 实现共用的底层工具：子进程执行、输出截断、POSIX 路径校验、引号处理与 options 读取。
 *
 * <p>子进程部分是 master {@code Computer.runProcess} 的等价物：异步读 stdout/stderr、
 * 超时 {@code destroyForcibly()} 强杀、读取线程有界 join。后一点很关键——podman 的后代进程
 * （conmon / 容器进程）可能继续占住管道写端，无超时的 join 会让执行线程永久阻塞。
 *
 * <p>之所以新增这个包内工具类：它不属于某一种形态，放进任一 Shell 都会让另两个反向依赖它。
 */
final class ShellSupport {

    private ShellSupport() {
    }

    /** 读取线程 join 上限（毫秒）：进程退出后管道通常立刻 EOF，这里的上限只为兜住 podman 后代进程。 */
    private static final long READER_JOIN_MILLIS = 5_000;

    /** 强杀后等待进程退出的上限（毫秒）。 */
    private static final long KILL_WAIT_MILLIS = 5_000;

    /** 超时/未指定时的默认命令超时。 */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    // ── 子进程 ──────────────────────────────────────────────────────────────

    /** 在进程默认工作目录执行。 */
    static Shell.CommandResult exec(List<String> command, String stdinInput,
                                    Duration timeout, int maxOutputChars) {
        return exec(command, stdinInput, timeout, maxOutputChars, null);
    }

    /**
     * 执行一条命令并返回三元组结果。
     *
     * <p>约定：超时或启动失败返回负退出码（-1）而不抛异常，错误信息放在 stderr 字段，
     * 由调用方决定如何呈现（{@code run} 必须容错，不能把异常抛成系统崩溃）。
     *
     * @param directory 子进程工作目录，null 表示继承当前进程目录（本地电脑用来对齐 {@code workdir()}）
     */
    static Shell.CommandResult exec(List<String> command, String stdinInput, Duration timeout,
                                    int maxOutputChars, Path directory) {
        Duration limit = (timeout == null || timeout.isZero() || timeout.isNegative())
                ? DEFAULT_TIMEOUT : timeout;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (directory != null) {
                builder.directory(directory.toFile());
            }
            Process process = builder.start();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outThread = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdout));
            Thread errThread = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr));

            if (stdinInput != null) {
                try (var writer = process.outputWriter(StandardCharsets.UTF_8)) {
                    writer.write(stdinInput);
                } catch (IOException ignored) {
                    // 进程提前退出（如路径不存在）时写管道会失败，错误已由退出码 / stderr 表达
                }
            }

            boolean finished = process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS);
            int exitCode;
            if (finished) {
                exitCode = process.exitValue();
            } else {
                process.destroyForcibly();
                process.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                exitCode = -1;
            }
            joinQuietly(outThread);
            joinQuietly(errThread);
            String outText = stdout.toString();
            String errText = stderr.toString();
            if (!finished) {
                // 超时信息随结果一起返回，调用方不必靠空输出猜原因（退出码已是非 0）
                errText += "命令执行超时（" + limit.toMillis() + " ms），已强制终止进程。\n";
            }
            return new Shell.CommandResult(exitCode,
                    truncate(outText, maxOutputChars),
                    truncate(errText, maxOutputChars));
        } catch (IOException e) {
            return new Shell.CommandResult(-1, "", "进程启动失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Shell.CommandResult(-1, "", "进程被中断: " + e.getMessage());
        }
    }

    private static void drain(InputStream in, StringBuilder sink) {
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sink.append(line).append('\n');
            }
        } catch (IOException ignored) {
            // 流随进程结束而关闭
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(READER_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 截断输出并注明被丢弃的字符数；{@code maxChars <= 0} 表示不截断（如 base64 整文件读取）。 */
    static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...[truncated " + (text.length() - maxChars) + " chars]";
    }

    /** PATH 上是否存在可执行文件（podman 检测，对齐 master {@code PodmanComputer.findExecutable}）。 */
    static boolean executableOnPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            try {
                if (Files.isExecutable(Path.of(dir, exe))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 非法 PATH 片段直接跳过
            }
        }
        return false;
    }

    // ── options 读取（缺省值 + 未知键忽略） ─────────────────────────────────

    /** 读取字符串选项：null / 空白都回退默认值，并去掉首尾空白。 */
    static String option(Map<String, String> options, String key, String fallback) {
        if (options == null) {
            return fallback;
        }
        String value = options.get(key);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    /** 依次尝试多个键名，返回第一个非空值（用于别名，如 container / container_name）。 */
    static String firstOption(Map<String, String> options, String fallback, String... keys) {
        if (keys != null) {
            for (String key : keys) {
                String value = option(options, key, "");
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return fallback;
    }

    /** 读取整数选项：解析失败回退默认值（不抛异常）。 */
    static int intOption(Map<String, String> options, String key, int fallback) {
        String value = option(options, key, "");
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ── 引号与路径 ──────────────────────────────────────────────────────────

    /** POSIX 单引号引用（shlex.quote 语义），供拼进 ssh 远端命令/脚本体时使用。 */
    static String quote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** 把 {@code ~/x} 形式的远端工作目录转成可内插的 shell word（{@code ~} 只有不被引号包住才会展开）。 */
    static String remoteWorkdirWord(String workdir) {
        String wd = (workdir == null || workdir.isBlank()) ? "~" : workdir.trim();
        if (wd.equals("~")) {
            return "\"$HOME\"";
        }
        if (wd.startsWith("~/")) {
            return "\"$HOME\"/" + quote(wd.substring(2));
        }
        return quote(wd);
    }

    /**
     * 容器内路径解析：空路径取工作目录；相对路径不得逃出工作目录；绝对路径放行
     * （容器里绝对路径本就是合法寻址方式）。
     *
     * @throws DomainError 相对路径经词法归一后越出工作目录（code {@code shell.path.escape}）
     */
    static String containerPath(String workdir, String path) {
        String wd = normalizePosix((workdir == null || workdir.isBlank()) ? "/" : workdir.trim());
        String raw = path == null ? "" : path.trim();
        if (raw.isEmpty()) {
            return wd;
        }
        if (raw.startsWith("/")) {
            return normalizePosix(raw);
        }
        String joined = normalizePosix(wd + "/" + raw);
        if (!within(wd, joined)) {
            throw escape(path);
        }
        return joined;
    }

    /**
     * SSH 相对路径校验：空路径取 "."；相对路径不得逃出工作目录（真正的解析交给远端 {@code cd workdir}）；
     * 绝对路径放行。
     *
     * @throws DomainError 相对路径归一后逃出工作目录（code {@code shell.path.escape}）
     */
    static String remoteRelativePath(String path) {
        String raw = path == null ? "" : path.trim();
        if (raw.isEmpty()) {
            return ".";
        }
        if (raw.startsWith("/")) {
            return normalizePosix(raw);
        }
        String normalized = normalizePosix(raw);
        if (normalized.equals("..") || normalized.startsWith("../")) {
            throw escape(path);
        }
        return normalized;
    }

    /**
     * 本地路径解析：绝对/相对路径都归一到工作目录之内；越界（含 {@code ..} 或绝对路径指向别处）
     * 抛 {@code shell.path.escape}。
     */
    static String localPath(String workdir, String path) {
        Path base = Path.of(workdir).toAbsolutePath().normalize();
        String raw = path == null ? "" : path.trim();
        Path candidate = Path.of(raw.isEmpty() ? "." : raw);
        Path resolved = (candidate.isAbsolute() ? candidate : base.resolve(candidate)).normalize();
        if (!resolved.startsWith(base)) {
            throw escape(path);
        }
        return resolved.toString();
    }

    /** 取父目录（用于 writeFile 自动建目录）：相对 "a" → "."，绝对 "/x" → "/"。 */
    static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return ".";
        }
        return slash == 0 ? "/" : path.substring(0, slash);
    }

    private static DomainError escape(String path) {
        return new DomainError("shell.path.escape", "路径越出工作目录: " + path);
    }

    private static boolean within(String workdir, String candidate) {
        if (candidate.equals(workdir)) {
            return true;
        }
        String prefix = workdir.endsWith("/") ? workdir : workdir + "/";
        return candidate.startsWith(prefix);
    }

    /** POSIX 路径词法归一（折叠 "." 与 ".."，纯字符串运算，不访问文件系统）。 */
    static String normalizePosix(String path) {
        String value = path == null ? "" : path;
        boolean absolute = value.startsWith("/");
        Deque<String> parts = new ArrayDeque<>();
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (!parts.isEmpty() && !parts.peekLast().equals("..")) {
                    parts.removeLast();
                } else if (!absolute) {
                    parts.addLast("..");
                }
                continue;
            }
            parts.addLast(segment);
        }
        String joined = String.join("/", parts);
        return absolute ? "/" + joined : joined;
    }

    /** 是否 Windows 宿主：本地电脑命令解释器据此在 sh 与 cmd 之间选择。 */
    static boolean isWindows() {
        return System.getProperty("os.name", "linux").toLowerCase(Locale.ROOT).contains("win");
    }
}
