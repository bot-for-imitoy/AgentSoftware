package com.agent.software.engine;

import com.agent.software.model.RoleSpec;

/**
 * 由一个 {@link RoleSpec} 装配出一个 {@link Agent} 的工厂。
 *
 * <p>实现放在 bootstrap：那里才知道用哪个 {@code ToolCatalog}、哪个 {@code Shell}、
 * 哪个 {@code LlmClient}。{@link Team} 只依赖这个接口，因此不认识适配器。
 */
public interface AgentFactory {

    Agent create(RoleSpec spec);
}
