package com.agent.software.utils;

import com.agent.software.store.ConfigStore;

import java.util.Map;

/**
 * Layered configuration resolution utility - the whole project reads config uniformly at the
 * following priority (high → low):
 * <ol>
 *   <li><b>Java arguments</b>: JVM system properties, e.g. {@code -DOPENAI_API_KEY=sk-...}
 *       (consistent with PathManager's system property fallback convention, key name = environment variable name);</li>
 *   <li><b>Environment variables</b>: e.g. {@code OPENAI_API_KEY};</li>
 *   <li><b>Config files</b>: {@link ConfigStore} dotted paths (e.g. {@code llm.api_key});</li>
 *   <li><b>Default values</b>.</li>
 * </ol>
 *
 * <p>All three sources use the same key name (envKey); an empty string is treated as unset. Tests can
 * inject fake environment variables / fake system properties via env / props snapshots (null = read the
 * real environment), consistent with PathManager's injection approach.
 */
public final class LayeredConfig {

    private LayeredConfig() {
    }

    /** String config: system property &gt; environment variable &gt; ConfigStore (tries storeKeys in order) &gt; default value. */
    public static String get(String envKey, ConfigStore store, String[] storeKeys, String def) {
        return get(envKey, store, storeKeys, def, null, null);
    }

    /**
     * String config (test injection variant).
     *
     * @param env   environment variable snapshot (null = System.getenv())
     * @param props system property snapshot (null = System.getProperty)
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

    /** Boolean config (accepts 1/true/yes/on, everything else treated as false). */
    public static boolean getBool(String envKey, ConfigStore store, String[] storeKeys,
                                  boolean def) {
        return getBool(envKey, store, storeKeys, def, null, null);
    }

    /** Boolean config (test injection variant). */
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
