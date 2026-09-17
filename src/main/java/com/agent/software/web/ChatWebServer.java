package com.agent.software.web;

import com.agent.software.company.CompanyView;
import com.agent.software.transcript.Transcript;
import com.agent.software.transcript.Transcript.Feed;

/**
 * JDK HttpServer，路由 /api/state /api/messages /api/reply /api/pause /api/resume /api/attach + 静态资源。
 */
public final class ChatWebServer {

    /** 绑定公司只读视图、轨迹 feed 与监听地址。 */
    public ChatWebServer(CompanyView view, Transcript.Feed feed, String host, int port) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 启动 HTTP 服务。 */
    public void start() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 停止 HTTP 服务。 */
    public void stop() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 实际监听端口（port=0 时为系统分配的端口）。 */
    public int port() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 实际监听地址。 */
    public String host() {
        throw new UnsupportedOperationException("skeleton");
    }
}
