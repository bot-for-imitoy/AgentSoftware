package com.agent.software.utils;

import com.agent.software.store.ConfigStore;

import java.util.Map;

/**
 * 分层配置解析工具 — 全项目配置统一按以下优先级读取 (高 → 低):
 * <ol>
 *   <li><b>Java 参数</b>: JVM 系统属性, 如 {@code -DDEEPSEEK_API_KEY=sk-...}
 *       (与 PathManager 的系统属性回退约定一致, 键名 = 环境变量名);</li>
 *   <li><b>环境变量</b>: 如 {@code DEEPSEEK_API_KEY};</li>
 *   <li><b>配置文件</b>: {@link ConfigStore} 点号路径 (如 {@code llm.deepseek.api_key});</li>
 *   <li><b>默认值</b>.</li>
 * </ol>
 *
 * <p>三个来源使用同一键名 (envKey); 空串视为未设置. 测试可通过 env / props 快照
 * 注入伪环境变量 / 伪系统属性 (null = 读取真实环境), 与 PathManager 的注入方式一致.
 */
public final class LayeredConfig {

    private LayeredConfig() {
    }

    /** 字符串配置: 系统属性 &gt; 环境变量 &gt; ConfigStore (依次尝试 storeKeys) &gt; 默认值. */
    public static String get(String envKey, ConfigStore store, String[] storeKeys, String def) {
        return get(envKey, store, storeKeys, def, null, null);
    }

    /**
     * 字符串配置 (测试注入版).
     *
     * @param env   环境变量快照 (null = System.getenv())
     * @param props 系统属性快照 (null = System.getProperty)
     */
    public static String get(String envKey, ConfigStore store, String[] storeKeys, String def,
                             Map<String, String> env, Map<String, String> props) {
        String prop = props != null ? fromSnapshot(props, envKey) : systemProperty(envKey);
        if (prop != null) {
            return prop;
        }
        String envVal = env != null ? fromSnapshot(env, envKey) : systemEnv(envKey);
        if (envVal != null) {
            return envVal;
        }
        for (String key : storeKeys) {
            Object v = store.get(key, null);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        return def;
    }

    /** 布尔配置 (支持 1/true/yes/on, 其余按 false). */
    public static boolean getBool(String envKey, ConfigStore store, String[] storeKeys,
                                  boolean def) {
        return getBool(envKey, store, storeKeys, def, null, null);
    }

    /** 布尔配置 (测试注入版). */
    public static boolean getBool(String envKey, ConfigStore store, String[] storeKeys,
                                  boolean def, Map<String, String> env, Map<String, String> props) {
        String s = get(envKey, store, storeKeys, null, env, props);
        if (s == null) {
            return def;
        }
        String v = s.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    private static String fromSnapshot(Map<String, String> snapshot, String key) {
        String v = snapshot.get(key);
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static String systemProperty(String key) {
        String v = System.getProperty(key, "");
        return v.isEmpty() ? null : v;
    }

    private static String systemEnv(String key) {
        String v = System.getenv().getOrDefault(key, "");
        return v.isEmpty() ? null : v;
    }
}
