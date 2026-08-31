package com.agent.software.computers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 电脑管理类 (Python 版 ComputerManager + create_computer 工厂).
 *
 * 职责: 分配/注册/查询/销毁各角色电脑; 确保 podman 自定义桥接网络存在;
 * 确保默认镜像存在 (不存在则从项目根 Containerfile 构建).
 */
public class ComputerManager {

    private static final Logger logger = LoggerFactory.getLogger(ComputerManager.class);

    public static final String DEFAULT_NETWORK_NAME = "maf-net";

    /** 镜像构建互斥锁文件 (跨进程串行 build). */
    private static final Path BASE_IMAGE_LOCK_FILE = Paths.get("data", ".maf-base-image.lock");

    private final ReentrantLock networkLock = new ReentrantLock();
    private final Map<String, Computer> computers = new LinkedHashMap<>(); // role_id → Computer
    private final Map<String, String> names = new LinkedHashMap<>();       // role_id → 人名

    public String networkName = DEFAULT_NETWORK_NAME;

    private ComputerManager() {
    }

    private static final ComputerManager INSTANCE = new ComputerManager();

    /** 全局单例: 角色自动创建的电脑统一注册到这里. */
    public static ComputerManager getInstance() {
        return INSTANCE;
    }

    // ── 按类型创建电脑实例 (create_computer 工厂) ──────────

    /**
     * 按类型创建电脑实例.
     *
     * @param kind     "podman" (默认) | "ssh" | "local".
     * @param roleId   角色标识.
     * @param autoMcp  是否自动创建 (True = 创建实例时自动安装独立 MCP 服务器).
     * @param kwargs   透传给具体实现 (ssh 需 host/user 等).
     */
    public static Computer createComputer(String kind, String roleId, boolean autoMcp,
                                          Map<String, Object> kwargs) {
        String k = (kind == null || kind.isEmpty()) ? "podman" : kind.toLowerCase();
        String name = strOf(kwargs.get("name"));
        String username = strOf(kwargs.get("username"));
        int uid = intOf(kwargs.get("uid"), 1100);
        switch (k) {
            case "local": {
                String baseDir = strOf(kwargs.get("base_dir"));
                String driveDir = strOf(kwargs.get("drive_dir"));
                return new Computer.LocalComputer(roleId, autoMcp,
                        baseDir.isEmpty() ? null : baseDir,
                        driveDir.isEmpty() ? null : driveDir,
                        name, username, uid);
            }
            case "ssh": {
                String host = strOf(kwargs.get("host"));
                if (host.isEmpty()) {
                    throw new IllegalArgumentException("SSHComputer 需要 host 参数 (远程主机地址)");
                }
                String user = strOf(kwargs.get("user"));
                String keyPath = strOf(kwargs.get("key_path"));
                int port = intOf(kwargs.get("port"), 22);
                return new SSHComputer(roleId, host,
                        user.isEmpty() ? null : user,
                        keyPath.isEmpty() ? null : keyPath,
                        port, autoMcp, name);
            }
            default: {
                String image = strOf(kwargs.get("image"));
                return new PodmanComputer(roleId,
                        image.isEmpty() ? null : image,
                        autoMcp, username, uid, name);
            }
        }
    }

    private static String strOf(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o));
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    // ── 网络 ──────────────────────────────────────────────

    /** 确保 podman 自定义桥接网络存在 (幂等). 返回网络名. */
    public String ensureNetwork() {
        if (PodmanComputer.findExecutable("podman") == null) {
            return networkName;  // 降级环境无 podman, 无所谓网络
        }
        networkLock.lock();
        try {
            Computer.ProcessResult r = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                r = Computer.runProcess(List.of("podman", "network", "exists", networkName), null, 30);
                if (r.returnCode == -1 || r.returnCode == -2 || r.returnCode == -3) {
                    logger.warn("podman network exists 超时/失败 (第 {} 次, 可能镜像构建中)", attempt);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    break;
                }
            }
            if (r != null && r.returnCode != 0) {
                Computer.runProcess(List.of("podman", "network", "create", networkName), null, 60);
                logger.info("podman 自定义桥接网络已创建: {}", networkName);
            }
        } finally {
            networkLock.unlock();
        }
        return networkName;
    }

    // ── 镜像 ──────────────────────────────────────────────

    /** 探测默认镜像是否存在. */
    public boolean imageExists() {
        Computer.ProcessResult r = Computer.runProcess(List.of("podman", "image", "exists", Computer.DEFAULT_IMAGE), null, 30);
        return r.returnCode == 0;
    }

    /** 轮询等待镜像出现 (构建方 build 期间探测命令排队, 超时不算失败). */
    private boolean waitImageAppears(long windowMillis) {
        long deadline = System.currentTimeMillis() + windowMillis;
        while (System.currentTimeMillis() < deadline) {
            if (imageExists()) {
                return true;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    /** 从项目根 Containerfile 构建默认镜像 (须持有文件锁). */
    private void buildBaseImage() {
        Path context = Paths.get(Computer.CONTAINERFILE).toAbsolutePath().getParent();
        Computer.ProcessResult r = Computer.runProcess(List.of("podman", "build", "-f", Computer.CONTAINERFILE,
                "-t", Computer.DEFAULT_IMAGE, context.toString()), null, 1800);
        if (r.returnCode != 0) {
            throw new RuntimeException("podman build 创建镜像 " + Computer.DEFAULT_IMAGE + " 失败 ("
                    + r.returnCode + "): " + Computer.truncate(
                    (r.stderr != null ? r.stderr : r.stdout), 300));
        }
        logger.info("自定义镜像 {} 已从 {} 构建 (角色容器从它复制)",
                Computer.DEFAULT_IMAGE, Computer.CONTAINERFILE);
    }

    /**
     * 确保电脑默认容器镜像存在 (由项目根 Containerfile 定义). 返回镜像名.
     * 并发保护: 文件锁 + 双检 — 首个调用者持锁完成构建; 其余调用者轮询等待.
     */
    public String ensureBaseImage() {
        if (PodmanComputer.findExecutable("podman") == null) {
            return Computer.DEFAULT_IMAGE;  // 降级环境无 podman
        }
        while (true) {
            if (tryAcquireImageLock()) {
                try {
                    if (imageExists()) {
                        return Computer.DEFAULT_IMAGE;
                    }
                    buildBaseImage();
                    return Computer.DEFAULT_IMAGE;
                } finally {
                    releaseImageLock();
                }
            }
            if (waitImageAppears(60_000)) {
                return Computer.DEFAULT_IMAGE;
            }
            // 单轮等待未出现: 回到抢锁流程 (构建方失败释放锁后由本进程接管)
        }
    }

    private FileChannel lockChannel = null;
    private FileLock lock = null;

    private synchronized boolean tryAcquireImageLock() {
        try {
            Files.createDirectories(BASE_IMAGE_LOCK_FILE.getParent());
            lockChannel = FileChannel.open(BASE_IMAGE_LOCK_FILE,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                lock = lockChannel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                lockChannel.close();
                lockChannel = null;
                return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private synchronized void releaseImageLock() {
        try {
            if (lock != null) {
                lock.release();
                lock = null;
            }
            if (lockChannel != null) {
                lockChannel.close();
                lockChannel = null;
            }
        } catch (IOException ignored) {
        }
    }

    // ── 分配 / 注册 ───────────────────────────────────────

    /** 创建并注册一台角色电脑 (分配). */
    public Computer create(String kind, String roleId, String name, boolean autoMcp,
                           Map<String, Object> kwargs) {
        ensureNetwork();
        Map<String, Object> merged = new LinkedHashMap<>(kwargs);
        if (name != null && !name.isEmpty()) {
            merged.putIfAbsent("name", name);
        }
        Computer comp = createComputer(kind, roleId, autoMcp, merged);
        register(comp, name);
        return comp;
    }

    /** 注册一台已创建的电脑到管理器. */
    public void register(Computer computer, String name) {
        computers.put(computer.roleId, computer);
        if (name != null && !name.isEmpty()) {
            names.put(computer.roleId, name);
        }
    }

    // ── 查询 ──────────────────────────────────────────────

    /** 按角色 ID 获取电脑 (不存在抛 IllegalArgumentException). */
    public Computer get(String roleId) {
        Computer c = computers.get(roleId);
        if (c == null) {
            throw new IllegalArgumentException("Role '" + roleId + "' has no computer");
        }
        return c;
    }

    /** 只读查询角色的显示名. */
    public String nameOf(String roleId, String def) {
        return names.getOrDefault(roleId, def != null ? def : roleId);
    }

    /** 返回全部已注册电脑列表 (按注册顺序). */
    public List<Computer> listAll() {
        return new ArrayList<>(computers.values());
    }

    // ── 销毁 ──────────────────────────────────────────────

    /** 销毁角色电脑: 关机 + 删除容器 + 注销. 返回是否销毁成功. */
    public boolean destroy(String roleId) {
        Computer comp = computers.remove(roleId);
        names.remove(roleId);
        if (comp == null) {
            return false;
        }
        try {
            if (comp.isOn()) {
                comp.powerOff();
            }
        } catch (Exception e) {
            logger.warn("电脑[{}] 关机失败 (销毁继续)", roleId);
        }
        if (comp instanceof PodmanComputer pc) {
            try {
                pc.pod("rm", "-f", pc.containerName);
                logger.info("电脑[{}] 容器已删除: {}", roleId, pc.containerName);
            } catch (Exception e) {
                logger.warn("电脑[{}] 容器删除失败", roleId);
            }
        }
        logger.info("电脑[{}] 已销毁 (注销)", roleId);
        return true;
    }

    // ── 内网设备 ──────────────────────────────────────────

    /** 列出内网电脑设备: 人名 / 电脑名 / IP (按角色排序). */
    public List<Map<String, String>> listLanDevices() {
        List<Map<String, String>> devices = new ArrayList<>();
        List<String> roleIds = new ArrayList<>(computers.keySet());
        roleIds.sort(String::compareTo);
        for (String roleId : roleIds) {
            Computer comp = computers.get(roleId);
            String ip = "";
            String computerName;
            if (comp instanceof PodmanComputer pc) {
                ip = pc.getLanIp();
                computerName = pc.containerName;
            } else if (comp instanceof SSHComputer sc) {
                ip = sc.host;
                computerName = "ssh-" + roleId.toLowerCase();
            } else {
                computerName = "local-" + roleId.toLowerCase();
            }
            Map<String, String> d = new LinkedHashMap<>();
            d.put("person", names.getOrDefault(roleId, roleId));
            d.put("role_id", roleId);
            d.put("computer", computerName);
            d.put("ip", ip.isEmpty() ? "(无内网IP)" : ip);
            devices.add(d);
        }
        return devices;
    }
}
