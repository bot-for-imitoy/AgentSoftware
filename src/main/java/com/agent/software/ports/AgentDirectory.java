package com.agent.software.ports;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.model.AgentState;
import com.agent.software.model.RoleSpec;

import java.util.List;
import java.util.Optional;

/**
 * 花名册只读视图。
 *
 * <p>工具（list_roles / mail_address_book）与 System Prompt 需要看同事，但不能改；
 * 只读视图切断了这条越权路径。
 */
public interface AgentDirectory {

    Optional<RoleSpec> spec(RoleId id);

    List<RoleSpec> specs();

    Optional<AgentState> stateOf(RoleId id);
}
