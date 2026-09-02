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
 * Computer manager class (Python version ComputerManager + create_computer factory).
 *
 * Responsibilities: allocate/register/query/destroy each role's computer; ensure the podman custom bridge
 * network exists; ensure the default image exists (build it from the project root Containerfile if missing).
 */
public class ComputerManager {

    private static final Logger logger = LoggerFactory.getLogger(ComputerManager.class);

    public static final String DEFAULT_NETWORK_NAME = "maf-net";

    /** Image build mutex lock file (serializes build across processes). */
    private static final Path BASE_IMAGE_LOCK_FILE = Paths.get("data", ".maf-base-image.lock");

    private final ReentrantLock networkLock = new ReentrantLock();
    private final Map<String, Computer> computers = new LinkedHashMap<>(); // role_id → Computer
    private final Map<String, String> names = new LinkedHashMap<>();       // role_id → person name

    public String networkName = DEFAULT_NETWORK_NAME;

    /**
     * One independent instance per system: the role computer registry is isolated per system, allowing multiple
     * AgentSystem instances to safely coexist (see {@link com.agent.software.AgentSystem}). Legacy code that does
     * not inject an instance can still use the process-level default singleton via {@link #getInstance()}.
     */
    public ComputerManager() {
    }

    private static final ComputerManager INSTANCE = new ComputerManager();

    /** Global singleton: computers auto-created by roles are all registered here. */
    public static ComputerManager getInstance() {
        return INSTANCE;
    }

    // ── Create computer instances by type (create_computer factory) ──────────

    /**
     * Create a computer instance by type.
     *
     * @param kind     "podman" (default) | "ssh" | "local".
     * @param roleId   Role identifier.
     * @param autoMcp  Whether it is auto-created (True = automatically install an independent MCP server when
     *                 creating the instance).
     * @param kwargs   Passed through to the concrete implementation (ssh requires host/user, etc.).
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
                    throw new IllegalArgumentException("SSHComputer requires a host parameter (remote host address)");
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

    // ── Network ──────────────────────────────────────────────

    /** Ensure the podman custom bridge network exists (idempotent). Returns the network name. */
    public String ensureNetwork() {
        if (PodmanComputer.findExecutable("podman") == null) {
            return networkName;  // degraded environment without podman, the network does not matter
        }
        networkLock.lock();
        try {
            Computer.ProcessResult r = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                r = Computer.runProcess(List.of("podman", "network", "exists", networkName), null, 30);
                if (r.returnCode == -1 || r.returnCode == -2 || r.returnCode == -3) {
                    logger.warn("podman network exists timed out/failed (attempt {}, image may be building)", attempt);
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
                logger.info("podman custom bridge network created: {}", networkName);
            }
        } finally {
            networkLock.unlock();
        }
        return networkName;
    }

    // ── Image ──────────────────────────────────────────────

    /** Check whether the default image exists. */
    public boolean imageExists() {
        Computer.ProcessResult r = Computer.runProcess(List.of("podman", "image", "exists", Computer.DEFAULT_IMAGE), null, 30);
        return r.returnCode == 0;
    }

    /** Poll and wait for the image to appear (probe commands queue while the builder runs build; timeouts are not treated as failures). */
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

    /** Build the default image from the project root Containerfile (the file lock must be held). */
    private void buildBaseImage() {
        Path context = Paths.get(Computer.CONTAINERFILE).toAbsolutePath().getParent();
        Computer.ProcessResult r = Computer.runProcess(List.of("podman", "build", "-f", Computer.CONTAINERFILE,
                "-t", Computer.DEFAULT_IMAGE, context.toString()), null, 1800);
        if (r.returnCode != 0) {
            throw new RuntimeException("podman build failed to create image " + Computer.DEFAULT_IMAGE + " ("
                    + r.returnCode + "): " + Computer.truncate(
                    (r.stderr != null ? r.stderr : r.stdout), 300));
        }
        logger.info("Custom image {} built from {} (role containers are copied from it)",
                Computer.DEFAULT_IMAGE, Computer.CONTAINERFILE);
    }

    /**
     * Ensure the default computer container image exists (defined by the project root Containerfile). Returns the
     * image name. Concurrency protection: file lock + double-check - the first caller holds the lock and completes
     * the build; the other callers poll and wait.
     */
    public String ensureBaseImage() {
        if (PodmanComputer.findExecutable("podman") == null) {
            return Computer.DEFAULT_IMAGE;  // degraded environment without podman
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
            // Image did not appear within one wait round: return to the lock-acquisition flow (if the builder failed
            // and released the lock, this process takes over)
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

    // ── Allocation / registration ───────────────────────────────────────

    /** Create and register a role computer (allocation). */
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

    /** Register an already-created computer with the manager. */
    public void register(Computer computer, String name) {
        computers.put(computer.roleId, computer);
        if (name != null && !name.isEmpty()) {
            names.put(computer.roleId, name);
        }
    }

    // ── Query ──────────────────────────────────────────────

    /** Get the computer by role ID (throws IllegalArgumentException if missing). */
    public Computer get(String roleId) {
        Computer c = computers.get(roleId);
        if (c == null) {
            throw new IllegalArgumentException("Role '" + roleId + "' has no computer");
        }
        return c;
    }

    /** Read-only lookup of a role's display name. */
    public String nameOf(String roleId, String def) {
        return names.getOrDefault(roleId, def != null ? def : roleId);
    }

    /** Return the list of all registered computers (in registration order). */
    public List<Computer> listAll() {
        return new ArrayList<>(computers.values());
    }

    // ── Destruction ──────────────────────────────────────────────

    /** Destroy a role computer: power off + delete container + unregister. Returns whether destruction succeeded. */
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
            logger.warn("Computer [{}] power-off failed (destruction continues)", roleId);
        }
        if (comp instanceof PodmanComputer pc) {
            try {
                pc.pod("rm", "-f", pc.containerName);
                logger.info("Computer [{}] container deleted: {}", roleId, pc.containerName);
            } catch (Exception e) {
                logger.warn("Computer [{}] container deletion failed", roleId);
            }
        }
        logger.info("Computer [{}] destroyed (unregistered)", roleId);
        return true;
    }

    // ── LAN devices ──────────────────────────────────────────

    /** List LAN computer devices: person name / computer name / IP (sorted by role). */
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
            d.put("ip", ip.isEmpty() ? "(no LAN IP)" : ip);
            devices.add(d);
        }
        return devices;
    }
}
