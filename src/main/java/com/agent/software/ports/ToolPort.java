package com.agent.software.ports;

import com.agent.software.kernel.RoleId;

import java.util.List;

/**
 * Tool invocation port.
 *
 * <p>The runtime asks which tools a role has and invokes them by name; assembly
 * of toolkits into specs is the tool service's concern, not the caller's.
 */
public interface ToolPort {

    List<ToolSpec> specs(RoleId role);

    ToolResult invoke(RoleId role, ToolCall call);
}
