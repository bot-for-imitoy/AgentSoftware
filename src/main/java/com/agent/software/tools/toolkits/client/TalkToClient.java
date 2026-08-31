package com.agent.software.tools.toolkits.client;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Tool;

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
 * 互斥: 同一时间只允许一位成员与甲方对话. 锁被占用时立即返回错误,
 * 不阻塞等待, 避免多人同时抢占控制台输入.
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
        } finally {
            lock.release(roleId);
        }
    }
}
