package com.agent.software.tool.hr;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.kernel.Ids.RoleId;

import java.util.List;

/**
 * 招聘能力（HR 工具的后端）。
 *
 * <p>master 的 {@code Hr} 工具直接持有 {@code RolePool} + {@code RoleFactory}；
 * 这里只暴露"看候选人 / 起草角色 / 使其上岗"三个动作。
 */
public interface Recruiter {

    /** 现有角色模板（候选人清单）。 */
    List<RoleSpec> candidates();

    /** 由招聘需求用 LLM 起草一个角色定义（不落库、不上岗）。 */
    RoleSpec draft(String requirement);

    /** 让角色正式上岗（注册 + 装配工具 + 启动 worker）。 */
    RoleId onboard(RoleSpec spec);
}
