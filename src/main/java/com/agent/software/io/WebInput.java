package com.agent.software.io;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 网页输入：按 target（会话标记）分组的阻塞队列。
 *
 * <p>呈现由 Web 实时推送负责；{@link #submit(String, String)} 是 Web 提交入口
 * （v3 冻结 API 里没有它，但不加就没人能把浏览器输入送进来，见报备清单）。
 */
public class WebInput extends Input {

    private static final long DEFAULT_TIMEOUT_MILLIS = 300_000L;

    private final Map<String, BlockingQueue<String>> queues = new ConcurrentHashMap<>();

    /** Web 提交一条输入；target 区分不同会话（如群组名或角色 id）。 */
    public void submit(String target, String text) {
        String key = target == null ? "" : target;
        queues.computeIfAbsent(key, k -> new LinkedBlockingQueue<>()).offer(text == null ? "" : text);
    }

    @Override
    public String read(String target) {
        String key = target == null ? "" : target;
        BlockingQueue<String> q = queues.computeIfAbsent(key, k -> new LinkedBlockingQueue<>());
        try {
            return q.poll(DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
