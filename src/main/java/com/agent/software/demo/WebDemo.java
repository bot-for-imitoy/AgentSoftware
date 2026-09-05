package com.agent.software.demo;

import com.agent.software.AgentSystem;
import com.agent.software.io.WebInput;
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
 * Web UI demo (lightweight): a minimal team (CEO/COO + frontend lead/frontend dev) + a chat server,
 * does not start role workers / does not create computers, manually simulating a few intra-team
 * messages and one Client A conversation.
 *
 * <pre>
 *   mvn exec:java -Dexec.mainClass=com.agent.software.demo.WebDemo
 * </pre>
 * Then open the address printed in the console; select "Leadership Group" on the left to see
 * the conversation between the CEO and you. The input box is enabled automatically, so you can
 * reply directly.
 */
public class WebDemo {

    public static void main(String[] args) throws Exception {
        // 1. Minimal team (autoToolkits=false: no computer/MCP toolkits, lightweight).
        //    Input = WebInput: the client replies in the Web page input box (talk_to_client waits for it).
        AgentSystem system = new AgentSystem(List.of(
                RoleLoader.getTemplate("CEO"),
                RoleLoader.getTemplate("COO"),
                RoleLoader.getTemplate("frontend_lead"),
                RoleLoader.getTemplate("frontend_dev_1")), null, 30.0, false, new WebInput());

        AgentRole ceo = system.getRole("CEO");
        AgentRole coo = system.getRole("COO");
        AgentRole fLead = system.getRole("frontend_lead");
        AgentRole fDev = system.getRole("frontend_dev_1");

        // 2. Manually assemble the talk / talk_to_client tools
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

        // 3. Start the Web server
        ChatWebServer web = new ChatWebServer(system);
        web.start();
        System.out.println("\n  Web UI started: http://127.0.0.1:" + web.port() + "/");
        System.out.println("  Select \"Leadership Group\" on the left to see the conversation between the CEO and you (the input box is enabled automatically);");
        System.out.println("  Select \"Frontend Development Group\" to see intra-team messages. Press Ctrl+C to exit.\n");

        // 4. Simulate intra-team messages (talk)
        talkFDev.handler(Map.of("target", "Chen Siyuan", "message", "The login page component refactor is done, please review.", "urgency", "NORMAL"));
        talkFDev.handler(Map.of("target", "Chen Siyuan", "message", "The mobile adaptation has been submitted; the attachment is in the cloud drive Public/mobile.md.", "urgency", "HIGH"));
        talkCeo.handler(Map.of("target", "Chen Zong", "message", "I received a new requirement from Client A; I will confirm the scope first and sync it to you later.", "urgency", "NORMAL"));

        // 5. Simulate a Client A conversation: CEO calls talk_to_client (Web mode); Client A replies "on the web page" 3 seconds later
        ChatStore store = system.chatStore;
        store.markAttached();
        AtomicReference<String> result = new AtomicReference<>();
        Thread ceoThread = new Thread(() -> result.set(
                talkToClient.handler(Map.of("message", "Hello, I am Lin Zong. We received your project requirements. Could you describe in detail the system you would like to develop?"))));
        ceoThread.start();
        if (waitUntil(store::isClientWaitPending, 5000)) {
            System.out.println("  [Client A] The CEO is waiting for your reply — you can now open the browser and reply in the \"Leadership Group\" input box.");
            System.out.println("  (The demo will automatically reply on your behalf after 8 seconds, so you can see the effect)");
            Thread.sleep(8000);
            store.postClientReply("Please help me develop an employee expense reimbursement approval system that supports mobile submission and manager approval.");
        }
        ceoThread.join(10_000);
        System.out.println("  CEO received the reply from Client A: " + result.get());

        // 6. Keep running until Ctrl+C
        CountDownLatch keepAlive = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            web.stop();
            system.stop();
            System.out.println("\nDemo finished ✓");
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
