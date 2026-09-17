package com.agent.software.tool.client;

import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.Toolkit;
import com.agent.software.transcript.Transcript;

import java.util.List;

/**
 * 客户沟通工具包（id {@code "client"}），暴露工具：talk_to_client（全局互斥的客户/用户对话通道）。
 */
public final class ClientToolkit implements Toolkit {

    private final ClientChannel client;
    private final Transcript transcript;

    public ClientToolkit(ClientChannel client, Transcript transcript) {
        this.client = client;
        this.transcript = transcript;
    }

    @Override
    public String id() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Tool> instantiate() {
        throw new UnsupportedOperationException("skeleton");
    }
}
