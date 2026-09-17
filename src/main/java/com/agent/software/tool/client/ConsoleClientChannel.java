package com.agent.software.tool.client;

import com.agent.software.kernel.Text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import com.agent.software.tool.client.ClientChannel.ClientQuestion;
import com.agent.software.tool.client.ClientChannel.ClientReply;

/**
 * 控制台客户通道：从 stdin 读取客户问题与回复，stdin 不可交互时视为离线。
 *
 * <p><b>互斥</b>：持有实例级 {@link ReentrantLock}。通道实例由 bootstrap 共享，
 * 因此"同一时刻只允许一位组长找客户"由这里保证——拿不到锁立刻返回，不阻塞排队。
 *
 * <p><b>超时</b>：阻塞读 stdin 在 Java 里没有可移植的超时手段（{@code System.in} 不可中断），
 * 所以 {@code timeout} 被忽略，{@code readLine()} 会一直阻塞到有输入或 EOF。
 * 需要真正超时的场景请使用 Web 通道（{@code ChatFeed.awaitClientReply(timeout)} 支持超时）。
 */
public final class ConsoleClientChannel implements ClientChannel {

    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public boolean interactive() {
        return System.console() != null;
    }

    @Override
    public ClientReply ask(ClientQuestion question, Duration timeout) {
        if (!lock.tryLock()) {
            return ClientReply.unavailable("已有同事正在与客户沟通");
        }
        try {
            if (!interactive()) {
                return ClientReply.unavailable("控制台不可交互");
            }
            String asker = question == null ? "同事" : Text.orEmpty(question.askerName());
            String text = question == null ? "" : Text.orEmpty(question.text());
            System.out.println();
            System.out.println("  [" + asker + "] " + text);
            System.out.print("  [客户] 请输入你的回复：");
            System.out.flush();

            String line = readLine();
            if (line == null) {
                return ClientReply.unavailable("无法读取控制台输入（非交互环境）");
            }
            return ClientReply.of(line.trim());
        } finally {
            lock.unlock();
        }
    }

    /** 读一行；流关闭（EOF）返回 null。 */
    private static String readLine() {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
