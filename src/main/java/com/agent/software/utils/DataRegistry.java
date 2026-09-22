package com.agent.software.utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 多态恢复注册表：type 字符串 → 工厂。
 *
 * <p>用 {@code Map<String,String>} 持久化的代价是丢掉类型信息，恢复时必须先知道要 new 哪个类。
 * Event 有子类 Task、Message 有三个子类，所以统一在这里登记。
 *
 * <p>工厂返回一个空实例，随后由 {@link Data#loadData(Map)} 回填字段。
 * 因此被持久化的类不能把字段声明成 final —— 详见报备项 B1。
 */
public final class DataRegistry {

    private static final Map<String, Supplier<? extends Data>> FACTORIES = new LinkedHashMap<>();

    private DataRegistry() {
    }

    public static void register(String type, Supplier<? extends Data> factory) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        FACTORIES.put(type, factory);
    }

    public static Data create(String type) {
        Supplier<? extends Data> factory = FACTORIES.get(type);
        if (factory == null) {
            throw new IllegalStateException("no Data factory registered for type: " + type);
        }
        return factory.get();
    }

    public static boolean supports(String type) {
        return type != null && FACTORIES.containsKey(type);
    }
}
