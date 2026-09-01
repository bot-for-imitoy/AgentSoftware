package com.agent.software.demo;

import com.agent.software.AgentSystem;
import com.agent.software.role.AgentRole;
import com.agent.software.role.RoleLoader;
import com.agent.software.tools.Tool;
import com.agent.software.tools.toolkits.client.Client;
import com.agent.software.tools.toolkits.talk.Talk;
import com.agent.software.web.ChatStore;
import com.agent.software.web.ChatWebServer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Web 界面演示 (轻量): 最小团队 (CEO/COO + 前端组长/前端开发) + 聊天服务器,
 * 不启动角色 worker / 不创建电脑, 手动模拟几条组内消息与一次甲方对话.
 *
 * <pre>
 *   mvn exec:java -Dexec.mainClass=com.agent.software.demo.WebDemo
 * </pre>
 * 然后打开控制台打印的地址, 左侧选择「领导组」即可看到 CEO 与您的对话,
 * 输入框自动启用, 可直接回复.
 */
public class WebDemo {

    public static void main(String[] args) throws Exception {
        // 1. 最小团队 (autoToolkits=false: 不装配电脑/MCP, 轻量)
        AgentSystem system = new AgentSystem(List.of(
                RoleLoader.getTemplate("CEO"),
                RoleLoader.getTemplate("COO"),
                RoleLoader.getTemplate("frontend_lead"),
                RoleLoader.getTemplate("frontend_dev_1")), null, 30.0, false);

        AgentRole ceo = system.getRole("CEO");
        AgentRole coo = system.getRole("COO");
        AgentRole fLead = system.getRole("frontend_lead");
        AgentRole fDev = system.getRole("frontend_dev_1");

        // 2. 手动装配 talk / talk_to_client 工具
        Talk ceoTalkTk = new Talk(ceo, system.pool);
        Talk cooTalkTk = new Talk(coo, system.pool);
        Talk fLeadTalkTk = new Talk(fLead, system.pool);
        Talk fDevTalkTk = new Talk(fDev, system.pool);
        ceo.addToolkit(ceoTalkTk);
        coo.addToolkit(cooTalkTk);
        fLead.addToolkit(fLeadTalkTk);
        fDev.addToolkit(fDevTalkTk);
        ceo.addToolkit(new Client(ceo));

        Tool talkFDev = fDevTalkTk.getTools().stream().filter(t -> "talk".equals(t.getToolName())).findFirst().orElseThrow();
        Tool talkCeo = ceoTalkTk.getTools().stream().filter(t -> "talk".equals(t.getToolName())).findFirst().orElseThrow();
        Tool talkToClient = new Client(ceo).getTools().stream().filter(t -> "talk_to_client".equals(t.getToolName())).findFirst().orElseThrow();

        // 3. 启动 Web 服务器
        ChatWebServer web = new ChatWebServer(system);
        web.start();
        System.out.println("\n  Web 界面已启动: http://127.0.0.1:" + web.port() + "/");
        System.out.println("  左侧选择「领导组」可看到 CEO 与您的对话 (输入框自动启用);");
        System.out.println("  选择「前端开发组」可看到组内消息。Ctrl+C 退出。\n");

        // 4. 模拟组内消息 (talk)
        talkFDev.handler(Map.of("target", "陈思远", "message", "登录页组件重构完成, 请验收。", "urgency", "NORMAL"));
        talkFDev.handler(Map.of("target", "陈思远", "message", "移动端适配已提交, 附件在云盘 Public/mobile.md。", "urgency", "HIGH"));
        talkCeo.handler(Map.of("target", "陈总", "message", "收到甲方新需求, 我会先确认范围, 稍后同步给你。", "urgency", "NORMAL"));

        // 5. 模拟甲方对话: CEO 调用 talk_to_client (Web 模式), 3 秒后甲方在"网页上"回复
        ChatStore store = system.chatStore;
        store.markAttached();
        AtomicReference<String> result = new AtomicReference<>();
        Thread ceoThread = new Thread(() -> result.set(
                talkToClient.handler(Map.of("message", "您好，我是林总。我们收到了您的项目需求，请问能否具体描述一下您想开发的系统？"))));
        ceoThread.start();
        if (waitUntil(store::isClientWaitPending, 5000)) {
            System.out.println("  [甲方] CEO 正在等您回复 —— 现在可以打开浏览器在「领导组」输入框回复。");
            System.out.println("  (演示将在 8 秒后自动代您回复一条, 便于查看效果)");
            Thread.sleep(8000);
            store.postClientReply("帮我开发一个员工报销审批系统，支持移动端提交与领导审批。");
        }
        ceoThread.join(10_000);
        System.out.println("  CEO 收到甲方回复: " + result.get());

        // 6. 保持运行直到 Ctrl+C
        CountDownLatch keepAlive = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            web.stop();
            system.stop();
            System.out.println("\n演示结束 ✓");
        }));
        keepAlive.await();
    }

    private static boolean waitUntil(java.util.function.Supplier<Boolean> pred, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(pred.get())) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }
}
