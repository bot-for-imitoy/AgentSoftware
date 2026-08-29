package com.maf.scheduler.tools;

import com.maf.scheduler.core.Json;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.core.ToolRegistry.ToolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 甲方交流工具类 (Client ToolKit) — Python 版 client_toolkit.py.
 *
 * 包含: talk_to_client (与甲方/用户实时交流, 控制台交互).
 */
public final class ClientToolkit {

    private static final Logger logger = LoggerFactory.getLogger(ClientToolkit.class);

    private static final String BOLD = "\033[1m";
    private static final String RESET = "\033[0m";

    private ClientToolkit() {
    }

    /** 创建甲方交流工具类. */
    public static ToolKit createClientToolkit() {
        ToolKit tk = new ToolKit("client", "甲方交流工具类: 与甲方(用户)实时交流");

        ToolHandler talkToClient = args -> {
            String question = Json.str(args, "message", "").strip();
            if (!question.isEmpty()) {
                System.out.println("\n  " + BOLD + "[CEO] " + question + RESET);
            } else {
                System.out.println("\n  " + BOLD + "[CEO] (发来一条消息, 请回复)" + RESET);
            }
            System.out.print("  [甲方] 请输入你的回复: ");
            System.out.flush();
            String reply;
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                reply = reader.readLine();
            } catch (IOException e) {
                return "错误: 无法获取用户输入 (非交互环境).";
            }
            if (reply == null) {
                return "错误: 无法获取用户输入 (非交互环境).";
            }
            reply = reply.strip();
            if (reply.isEmpty()) {
                return "甲方未输入内容 (空回复).";
            }
            logger.info("甲方回复: {}", reply.length() > 80 ? reply.substring(0, 80) : reply);
            return "甲方回复: " + reply;
        };

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "message", TalkToolkit.mapOf("string", "想对甲方说的话 (可选, 如提问/汇报内容)")));

        tk.addPythonTool("talk_to_client",
                "与甲方(用户)实时交流. 调用此工具会暂停并请求用户输入文本, "
                        + "用户输入的内容会作为结果返回给你. "
                        + "用于: 收集需求, 确认方案, 汇报进度, 提出疑问. "
                        + "如果需要向用户展示信息, 请先通过 message 参数说明.",
                schema, talkToClient);
        return tk;
    }
}
