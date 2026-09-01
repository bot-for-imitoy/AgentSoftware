package com.agent.software.tools.toolkits.client;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Tool;
import com.agent.software.tools.Toolkits;
import com.agent.software.web.ChatStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * talk_to_client — 与甲方(用户)实时交流. 调用此工具会暂停并请求用户输入
 * 文本, 用户输入的内容会作为结果返回.
 * 用于: 收集需求, 确认方案, 汇报进度, 提出疑问.
 *
 * <p>双通道输入:
 * <ul>
 *   <li><b>Web 模式</b>: Web 界面已挂载 (ChatStore 心跳内) 时, 消息显示在
 *       Web 聊天窗口, 输入框自动启用, 甲方在网页上回复 → 结果返回.
 *       等待有超时 ({@code AGENTCOMPANY_CLIENT_REPLY_TIMEOUT}, 默认 20 分钟),
 *       超时返回错误, 避免代理无限阻塞.</li>
 *   <li><b>控制台模式</b>: 未挂载 Web 时保持原行为, 阻塞读取 System.in.</li>
 * </ul>
 *
 * 互斥: 同一时间只允许一位成员与甲方对话. 锁被占用时立即返回错误,
 * 不阻塞等待, 避免多人同时抢占输入.
 */
public class TalkToClient extends Tool {

    private static final String BOLD = "\033[1m";
    private static final String RESET = "\033[0m";

    private final AgentRole agentRole;
    private final ClientCommunicationLock lock;

    public TalkToClient(AgentRole agentRole) {
        this(agentRole, ClientCommunicationLock.getInstance());
    }

    /** 包私有: 测试可注入独立锁实例. */
    TalkToClient(AgentRole agentRole, ClientCommunicationLock lock) {
        super();
        this.agentRole = agentRole;
        this.lock = lock;
    }

    @Override
    public String getToolName() {
        return "talk_to_client";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("message", "(Optional) What you want to say to the client (e.g. a question or progress report).");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        String roleId = agentRole != null ? agentRole.roleId : "";
        String name = agentRole != null ? agentRole.name : "Client";
        String conflict = lock.tryAcquire(roleId, name);
        if (conflict != null) {
            return "talk_to_client: Error: " + conflict + ", try again later.";
        }
        try {
            Object omsg = args.get("message");
            String question = omsg instanceof String s ? s.strip() : "";
            ChatStore store = chatStore();
            // 记录消息到聊天存储 (Web 界面展示; 控制台模式也留痕)
            if (store != null) {
                String group = agentRole != null && agentRole.group != null
                        && !agentRole.group.isBlank() ? agentRole.group : Toolkits.LEADERSHIP_GROUP;
                store.record(ChatStore.KIND_CLIENT, group, roleId, name, "", ChatStore.CLIENT_NAME,
                        question.isEmpty() ? "(发来一条消息, 请回复)" : question, null);
            }
            // Web 模式: 已有 Web 界面挂载 → 等待甲方在网页上回复
            if (store != null && store.isAttached()) {
                return handlerWeb(store, roleId, name, question);
            }
            // 控制台模式 (原行为): 阻塞读取 System.in
            return handlerConsole(name, question);
        } finally {
            lock.release(roleId);
        }
    }

    /** Web 模式: 登记等待 → 阻塞等甲方在网页上回复 (带超时). */
    private String handlerWeb(ChatStore store, String roleId, String name, String question) {
        String group = agentRole != null && agentRole.group != null
                && !agentRole.group.isBlank() ? agentRole.group : Toolkits.LEADERSHIP_GROUP;
        String beginErr = store.beginClientWait(roleId, name, group);
        if (beginErr != null) {
            return "talk_to_client: Error: " + beginErr + ", try again later.";
        }
        try {
            long timeoutMs = com.agent.software.web.ChatWebServer.replyTimeoutMs();
            String reply;
            try {
                reply = store.awaitClientReply(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "talk_to_client: Error: interrupted while waiting for the client reply.";
            }
            if (reply == null) {
                return "talk_to_client: Error: the client did not reply within "
                        + (timeoutMs / 1000) + "s (web interface). Please contact the client again later.";
            }
            return "talk_to_client: client reply: " + reply;
        } finally {
            store.endClientWait();
        }
    }

    /** 控制台模式: 原 System.in 交互. */
    private String handlerConsole(String name, String question) {
        if (!question.isEmpty()) {
            System.out.println("\n  " + BOLD + "[" + name + "] " + question + RESET);
        } else {
            System.out.println("\n  " + BOLD + "[" + name + "] (发来一条消息, 请回复)" + RESET);
        }
        System.out.print("  [甲方] 请输入你的回复: ");
        System.out.flush();
        String reply;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            reply = reader.readLine();
        } catch (IOException e) {
            return "talk_to_client: Error: cannot get user input (non-interactive environment).";
        }
        if (reply == null) {
            return "talk_to_client: Error: cannot get user input (non-interactive environment).";
        }
        reply = reply.strip();
        if (reply.isEmpty()) {
            return "talk_to_client: the client did not enter anything (empty reply).";
        }
        return "talk_to_client: client reply: " + reply;
    }

    /** 本角色所属系统的聊天存储 (未绑定上下文的独立角色为 null). */
    private ChatStore chatStore() {
        return agentRole != null && agentRole.context() != null
                ? agentRole.context().chatStore : null;
    }
}
