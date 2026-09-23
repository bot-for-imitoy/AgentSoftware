package com.agent.software;

import com.agent.software.event.Priority;
import com.agent.software.event.Task;
import com.agent.software.io.WebInput;
import com.agent.software.role.Role;
import com.agent.software.web.ChatWebServer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 主入口：启动默认大组（管理组）的模拟，并起 Web UI。
 */
public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("AgentSoftware — shift & event driven agent scheduler");
        WebInput input = new WebInput();
        AgentSystem system = new AgentSystem(null, input);
        System.out.println("Cohort: " + system.getRolePool().size() + " role(s)");
        for (Role r : system.getRolePool().all()) {
            System.out.println("  - " + r.roleId + " | " + r.name + " | " + r.group);
        }
        System.out.println("Config file: " + system.getConfigStore().getPath()
                + (java.nio.file.Files.exists(system.getConfigStore().getPath()) ? "" : "  (not found — create it with llm.api_key / llm.base_url / llm.model)"));

        final ChatWebServer[] webHolder = new ChatWebServer[1];
        try {
            // 监听地址/端口：agentsoftware.webHost / AGENTSOFTWARE_WEB_HOST（默认 0.0.0.0）
            //              agentsoftware.webPort / AGENTSOFTWARE_WEB_PORT（默认 8787，被占用则随机）
            ChatWebServer web = new ChatWebServer(system);
            web.start();
            webHolder[0] = web;
            printWebUrls(web);
        } catch (Exception e) {
            System.out.println("Web UI failed to start: " + e.getMessage());
        }

        // 开局任务：第 1 天 09:00 让 CEO 找甲方沟通。
        // 不排这个任务的话，全员空闲会让时钟一路快进（08:00 → 18:00 → 次日），什么都不会发生。
        long kickoffTick = system.getTimeBus().getShiftStartTick() + 3_600L;   // shiftStart(=08:00) + 1h = 09:00
        Task kickoff = new Task("system", "CEO", kickoffTick,
                "It is 09:00. Use talk_to_client to greet the client (Client A) and collect today's project "
                        + "requirements. When you have them, briefly summarize the plan and hand work to the team.",
                Priority.HIGH);
        system.getEventBus().schedule(kickoff);
        System.out.println("Scheduled CEO kickoff at tick " + kickoffTick + " (09:00)");

        system.start();
        System.out.println("System started: " + system.getTimeBus().currentDateTime()
                + " (shift " + system.getTimeBus().getShiftStartTick()
                + " → " + system.getTimeBus().getShiftEndTick() + " ticks/day, timeScale="
                + system.getTimeBus().getTimeScale() + "x; override with AGENTSOFTWARE_TIME_SCALE)");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            system.stop();
            if (webHolder[0] != null) {
                webHolder[0].stop();
            }
        }));

        // 演示循环：每隔一段时间打印一次时钟与角色状态
        while (true) {
            Thread.sleep(30_000);
            System.out.println("[" + system.getTimeBus().currentDateTime() + "] cohort states:");
            for (Role r : system.getRolePool().all()) {
                System.out.println("  " + r.roleId + ": " + r.getState() + ", queue=" + r.queueDepth());
            }
        }
    }

    /**
     * 打印可用的访问地址：本机 localhost + 所有非回环 IPv4（绑 0.0.0.0 时局域网里直接用后者的 URL）。
     *
     * <p>固定端口就是为了让这个 URL 可以记住/发给别人，所以必须把真实地址打全 ——
     * 只打 {@code 0.0.0.0} 是没有意义的。
     */
    static void printWebUrls(ChatWebServer web) {
        int port = web.port();
        System.out.println("Web UI: http://localhost:" + port + "/");
        List<String> lanHosts = new ArrayList<>();
        String host = web.host() == null ? "" : web.host();
        if (host.equals("0.0.0.0") || host.equals("::") || host.isBlank()) {
            lanHosts.addAll(localIpv4Addresses());
        } else if (!host.equals("127.0.0.1") && !host.equalsIgnoreCase("localhost")) {
            lanHosts.add(host);
        }
        for (String h : lanHosts) {
            System.out.println("        http://" + h + ":" + port + "/   (LAN)");
        }
        System.out.println("        (listening on " + host + ":" + port + "; override with "
                + ChatWebServer.HOST_ENV + " / " + ChatWebServer.PORT_ENV + ")");
    }

    /** 所有 up 的非回环 IPv4 地址（局域网/IPv4 通常就够用，IPv6 不列）。 */
    private static List<String> localIpv4Addresses() {
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
        } catch (Exception e) {
            // 列不出来就只留 localhost，不影响启动
        }
        return out;
    }
}
