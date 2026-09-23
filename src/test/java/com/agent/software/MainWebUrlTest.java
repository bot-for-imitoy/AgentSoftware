package com.agent.software;

import com.agent.software.io.WebInput;
import com.agent.software.web.ChatWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 启动时打印的 Web UI 地址（固定端口就是为了这个 URL 能记住/发给局域网里的别人）。 */
class MainWebUrlTest {

    private static String capture(Runnable action) {
        PrintStream old = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(old);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** 测试机能找到的非回环 IPv4（列表为空时跳过 LAN 断言，CI 里可能没有网卡）。 */
    private static List<String> localIpv4() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        out.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    @Test
    void printsLocalhostAndEveryLanAddress(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        String oldHost = System.getProperty("agentsoftware.webHost");
        String oldPort = System.getProperty("agentsoftware.webPort");
        try {
            System.setProperty("agentsoftware.webHost", "0.0.0.0");
            System.setProperty("agentsoftware.webPort", "0");
            ChatWebServer web = new ChatWebServer(system);
            try {
                web.start();
                int port = web.port();
                String out = capture(() -> Main.printWebUrls(web));
                assertTrue(out.contains("Web UI: http://localhost:" + port + "/"), out);
                assertTrue(out.contains("listening on 0.0.0.0:" + port), out);
                assertTrue(out.contains(ChatWebServer.HOST_ENV) && out.contains(ChatWebServer.PORT_ENV), out);
                List<String> lan = localIpv4();
                if (!lan.isEmpty()) {
                    for (String ip : lan) {
                        assertTrue(out.contains("http://" + ip + ":" + port + "/"),
                                "绑 0.0.0.0 时要打印局域网地址 " + ip + ": " + out);
                    }
                }
            } finally {
                web.stop();
            }

            // 只绑回环时不该给局域网地址（免得误导）
            System.setProperty("agentsoftware.webHost", "127.0.0.1");
            ChatWebServer loopback = new ChatWebServer(system);
            try {
                loopback.start();
                String out = capture(() -> Main.printWebUrls(loopback));
                assertTrue(out.contains("http://localhost:" + loopback.port() + "/"), out);
                assertTrue(!out.contains("(LAN)"), "绑回环时不应打印局域网地址: " + out);
            } finally {
                loopback.stop();
            }
        } finally {
            if (oldHost == null) {
                System.clearProperty("agentsoftware.webHost");
            } else {
                System.setProperty("agentsoftware.webHost", oldHost);
            }
            if (oldPort == null) {
                System.clearProperty("agentsoftware.webPort");
            } else {
                System.setProperty("agentsoftware.webPort", oldPort);
            }
            system.stop();
        }
    }
}
