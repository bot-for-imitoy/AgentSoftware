package com.agent.software.ports;

import com.agent.software.model.Payload;
import com.agent.software.model.ToolResult;
import com.agent.software.model.ToolSpec;

import java.util.List;

/**
 * 某个角色已装配好的工具集合。
 *
 * <p>工具循环只认识这个接口，不需要知道工具来自内置工具包还是 MCP 代理。
 */
public interface Toolbox {

    List<ToolSpec> specs();

    ToolResult invoke(String toolName, Payload arguments);
}
