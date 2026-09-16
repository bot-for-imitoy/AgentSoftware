package com.agent.software.bootstrap;

import com.agent.software.adapters.config.AppConfig;
import com.agent.software.engine.Company;
import com.agent.software.model.RoleSpec;

import java.util.List;

/**
 * 入口：加载配置 → 组装公司 → 招聘默认团队 → 读档 → 启动 → 日循环 → 退出落盘。
 *
 * <p>对应 master {@code Main}，但业务步骤全部下移到 {@link Company}，
 * 这里只做"驱动一次模拟"的编排。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 由配置构建一个公司（供 main 与测试复用）。 */
    static Company buildCompany(AppConfig config) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 从 classpath 角色模板加载默认团队。 */
    static List<RoleSpec> defaultTeam(AppConfig config) {
        throw new UnsupportedOperationException("skeleton");
    }
}
