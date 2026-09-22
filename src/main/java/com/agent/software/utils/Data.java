package com.agent.software.utils;

import java.util.Map;

/**
 * 需要持久化的类统一实现这个接口。
 *
 * <p>只返回字符串键值：嵌套结构（事件 payload、tool_calls、消息列表等）由实现方自行
 * 序列化成 JSON 字符串再放进来。这样数据库层完全不需要理解业务类型。
 *
 * <p>{@link #getData()} 与 {@link #loadData(Map)} 必须成对实现：前者存，后者恢复。
 * 有多态子类的类型（Event/Task、Message 的三个子类）在恢复时先读 "type" 字段，
 * 再交给 {@link DataRegistry} 创建具体实例。
 */
public interface Data {

    Map<String, String> getData();

    void loadData(Map<String, String> data);
}
