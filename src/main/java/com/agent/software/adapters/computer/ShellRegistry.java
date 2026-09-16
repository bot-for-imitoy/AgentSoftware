package com.agent.software.adapters.computer;

import com.agent.software.adapters.config.AppPaths;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.model.RoleSpec;
import com.agent.software.ports.Shell;

import java.util.List;

/**
 * 角色电脑注册表：按 ComputerSpec 创建/销毁 Shell，维护 roleId → Shell 映射。
 */
public final class ShellRegistry {

    /** 绑定路径解析器与容器共享网络名。 */
    public ShellRegistry(AppPaths paths, String networkName) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 按角色定义创建（或复用）其个人电脑。 */
    public Shell create(RoleSpec spec) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 销毁并移除某角色的电脑。 */
    public void destroy(RoleId id) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 当前全部电脑。 */
    public List<Shell> all() {
        throw new UnsupportedOperationException("skeleton");
    }
}
