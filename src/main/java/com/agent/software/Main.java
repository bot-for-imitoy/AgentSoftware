package com.agent.software;

import com.agent.software.io.WebInput;
import com.agent.software.role.Role;
import com.agent.software.web.ChatWebServer;

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

        final ChatWebServer[] webHolder = new ChatWebServer[1];
        try {
            ChatWebServer web = new ChatWebServer(system, "127.0.0.1", 0);
            web.start();
            webHolder[0] = web;
            System.out.println("Web UI: http://127.0.0.1:" + web.port() + "/");
        } catch (Exception e) {
            System.out.println("Web UI failed to start: " + e.getMessage());
        }

        system.start();
        System.out.println("System started: " + system.getTimeBus().currentDateTime()
                + " (shift " + system.getTimeBus().getShiftStartTick()
                + " → " + system.getTimeBus().getShiftEndTick() + " ticks/day)");

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
}
