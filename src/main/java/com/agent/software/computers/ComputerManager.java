package com.agent.software.computers;

import com.agent.software.role.Role;
import com.agent.software.utils.UUIDObjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 电脑注册表：创建 / 移除 / 查找 / 组网 / 基础镜像。
 *
 * <p>销毁动作在 {@link Computer#destroy()}，这里只负责把它移出注册表。
 */
public class ComputerManager extends UUIDObjectManager<Computer> {

    private static final Logger logger = LoggerFactory.getLogger(ComputerManager.class);

    private static final String NETWORK_NAME = "agentsoftware-net";

    public ComputerManager() {
    }

    public Computer create(String kind, Role role) {
        String k = kind == null || kind.isBlank() ? "podman" : kind.toLowerCase();
        Computer c = switch (k) {
            case "local" -> new LocalComputer(role);
            case "ssh" -> new LocalComputer(role);   // SSH 实现后补，先本地
            default -> new PodmanComputer(role);
        };
        add(c);
        return c;
    }

    public void remove(String roleId) {
        Computer c = findByRoleId(roleId);
        if (c == null) {
            return;
        }
        try {
            c.destroy();
        } catch (Exception e) {
            logger.warn("ComputerManager: destroy failed for {}", roleId, e);
        }
        remove(c);
    }

    public Computer findByRoleId(String roleId) {
        if (roleId == null) {
            return null;
        }
        for (Computer c : all()) {
            if (c.getRole() != null && roleId.equals(c.getRole().roleId)) {
                return c;
            }
        }
        return null;
    }

    public String nameOf(String roleId, String def) {
        Computer c = findByRoleId(roleId);
        if (c instanceof PodmanComputer podman) {
            return podman.containerName;
        }
        return def;
    }

    public List<Map<String, String>> listLanDevices() {
        List<Map<String, String>> out = new ArrayList<>();
        for (Computer c : all()) {
            Map<String, String> m = new LinkedHashMap<>();
            Role r = c.getRole();
            m.put("role_id", r == null ? "" : r.roleId);
            m.put("name", r == null ? "" : r.name);
            m.put("computer", c.getClass().getSimpleName());
            m.put("on", Boolean.toString(c.isOn()));
            out.add(m);
        }
        return out;
    }

    public boolean hasNetwork() {
        return podmanOk("network", "exists", NETWORK_NAME);
    }

    public void createNetwork() {
        if (!hasNetwork()) {
            podman("network", "create", NETWORK_NAME);
        }
    }

    public boolean hasBaseImage() {
        return podmanOk("image", "exists", System.getenv()
                .getOrDefault("AGENTSOFTWARE_BASE_IMAGE", "agentsoftware-base:latest"));
    }

    public void buildBaseImage() {
        String image = System.getenv().getOrDefault("AGENTSOFTWARE_BASE_IMAGE", "agentsoftware-base:latest");
        String containerfile = System.getenv().getOrDefault("AGENTSOFTWARE_CONTAINERFILE", "Containerfile");
        podman("build", "-t", image, "-f", containerfile, ".");
    }

    private boolean podmanOk(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("podman");
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void podman(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("podman");
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            p.getInputStream().readAllBytes();
            p.waitFor();
        } catch (Exception e) {
            logger.warn("podman {} failed: {}", String.join(" ", args), e.toString());
        }
    }
}
