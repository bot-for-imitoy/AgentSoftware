package com.agent.software.tool.computer;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.RoleId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色电脑注册表：按 ComputerSpec 创建/销毁 Shell，维护 roleId → Shell 映射。
 *
 * <p>对齐 master {@code ComputerManager}：容器网络名默认 {@code agentsoftware-net}（在
 * {@link PodmanShell} 里落地），kind 分支只认 {@code podman} / {@code ssh}，
 * 其余（{@code local}、null、空串、未知值）一律回退本地目录形态。
 *
 * <p>同一 roleId 重复 {@link #create(RoleSpec)} 复用同一实例（{@code ConcurrentHashMap}），
 * 因此并发的角色装配不会重复建容器。
 */
public final class ShellRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ShellRegistry.class);

    /** 默认容器共享网络名（对齐 master {@code ComputerManager.DEFAULT_NETWORK_NAME}）。 */
    public static final String DEFAULT_NETWORK = "agentsoftware-net";

    private final AppPaths paths;
    private final String networkName;
    private final ConcurrentHashMap<String, Shell> shells = new ConcurrentHashMap<>();

    /** 绑定路径解析器与容器共享网络名。 */
    public ShellRegistry(AppPaths paths, String networkName) {
        this.paths = paths;
        this.networkName = (networkName == null || networkName.isBlank())
                ? DEFAULT_NETWORK : networkName.trim();
    }

    /** 按角色定义创建（或复用）其个人电脑。 */
    public Shell create(RoleSpec spec) {
        if (spec == null || spec.id() == null) {
            throw new DomainError("shell.role.missing", "创建电脑需要角色定义（含 RoleId）");
        }
        return shells.computeIfAbsent(spec.id().value(), key -> instantiate(spec));
    }

    private Shell instantiate(RoleSpec spec) {
        RoleSpec.ComputerSpec computer = spec.computer();
        String kind = (computer == null || computer.kind() == null)
                ? "" : computer.kind().trim().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "podman" -> new PodmanShell(spec.id(), computer, paths, networkName);
            case "ssh" -> new SshShell(spec.id(), computer);
            // "local"、null、空串以及未知 kind 都回退本地目录形态（严格契约）
            default -> new LocalShell(spec.id(), computer, paths);
        };
    }

    /**
     * 销毁并移除某角色的电脑。
     *
     * <p>先从注册表移除再关机：关机失败不能让条目泄漏在注册表里；关机异常只记日志。
     */
    public void destroy(RoleId id) {
        if (id == null) {
            return;
        }
        Shell shell = shells.remove(id.value());
        if (shell == null) {
            return;
        }
        try {
            shell.powerOff();
        } catch (RuntimeException e) {
            logger.warn("电脑 [{}] 关机失败（已从注册表移除）: {}", id.value(), e.getMessage());
        }
    }

    /** 当前全部电脑。 */
    public List<Shell> all() {
        return List.copyOf(shells.values());
    }
}
