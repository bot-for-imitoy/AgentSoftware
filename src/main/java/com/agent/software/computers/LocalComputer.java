package com.agent.software.computers;

import com.agent.software.role.Role;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 本地目录电脑：不需要容器，直接在 host 的一个目录里执行命令/读写文件。
 *
 * <p>默认 kind 就是它（避免无 podman 环境下直接失败）。
 */
public class LocalComputer extends Computer {

    public LocalComputer(Role role) {
        super(role);
    }

    public LocalComputer(Role role, String uuid) {
        super(role, uuid);
    }

    @Override
    public void powerOn() {
        ensureDir(hostDir());
        ison = true;
    }

    @Override
    public void powerOff() {
        ison = false;
    }

    @Override
    public void destroy() {
        ison = false;
    }

    @Override
    public String runCommand(String command, int timeout, int maxChars) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-lc", command);
            pb.directory(hostDir().toFile());
            Process p = pb.start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "[timeout] " + command;
            }
            String combined = out + (err.isBlank() ? "" : "\n[stderr] " + err);
            return maxChars > 0 && combined.length() > maxChars
                    ? combined.substring(0, maxChars) : combined;
        } catch (IOException e) {
            return "[error] " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[interrupted]";
        }
    }

    @Override
    public String readFile(String path) {
        try {
            return Files.readString(resolve(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "[error] " + e.getMessage();
        }
    }

    @Override
    public void writeFile(String path, String content) {
        try {
            Path target = resolve(path);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("cannot write file: " + path, e);
        }
    }

    @Override
    public String listDir(String path) {
        Path dir = resolve(path);
        if (!Files.isDirectory(dir)) {
            return "[error] not a directory: " + path;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.map(Path::getFileName).map(Path::toString)
                    .sorted().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "[error] " + e.getMessage();
        }
    }

    @Override
    public void deleteFile(String path) {
        try {
            Files.deleteIfExists(resolve(path));
        } catch (IOException e) {
            throw new RuntimeException("cannot delete file: " + path, e);
        }
    }

    private Path resolve(String path) {
        if (path == null || path.isBlank()) {
            return hostDir();
        }
        Path p = Path.of(path);
        return p.isAbsolute() ? p : hostDir().resolve(path);
    }

    private static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("cannot create computer dir: " + dir, e);
        }
    }
}
