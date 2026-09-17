package com.agent.software.company;

import com.agent.software.agent.role.RoleSpec;

import java.util.List;

/**
 * 公司只读视图 + 全局暂停控制（Web / 控制台适配器使用的窄接口）。
 *
 * <p>存在的理由：让 {@code web} 能读取状态、暂停/恢复，
 * 而**不必依赖 company.Company**；{@code company.Company} 实现它。
 */
public interface CompanyView {

    CompanyStatus status();

    /** 当前在册角色（Web 分组列表用）。 */
    List<RoleSpec> roster();

    void pause(String reason);

    void resume();
}
