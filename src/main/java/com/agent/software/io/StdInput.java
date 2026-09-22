package com.agent.software.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 控制台输入：从标准流读一行。
 *
 * <p>持有单个 reader 字段 —— 每次调用都 new BufferedReader 会吞掉缓冲区里的字节，
 * 导致偶发丢输入。
 */
public class StdInput extends Input {

    private final BufferedReader reader =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    @Override
    public String read(String target) {
        try {
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
