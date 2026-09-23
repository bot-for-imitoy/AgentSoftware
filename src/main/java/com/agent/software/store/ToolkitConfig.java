package com.agent.software.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 默认工具集配置：
 * <pre>
 * {
 *   "default_toolkits": ["time", "task", "note", ...],
 *   "by_group": { "Leadership Group": [...] },
 *   "by_role":  { "COO": [...] }
 * }
 * </pre>
 * 覆盖顺序：default → by_group → by_role。
 */
public final class ToolkitConfig {

    public static final String DEFAULTS = "default_toolkits";
    public static final String BY_GROUP = "by_group";
    public static final String BY_ROLE = "by_role";

    private final JsonStore store;

    public ToolkitConfig(JsonStore store) {
        this.store = store;
    }

    public List<String> defaultsFor(String roleId, String group) {
        List<String> out = new ArrayList<>(stringList(store.get(DEFAULTS)));
        Map<String, Object> byGroup = store.get(BY_GROUP);
        if (group != null && byGroup.get(group) != null) {
            out = new ArrayList<>(stringList(byGroup.get(group)));
        }
        Map<String, Object> byRole = store.get(BY_ROLE);
        if (roleId != null && byRole.get(roleId) != null) {
            out = new ArrayList<>(stringList(byRole.get(roleId)));
        }
        return out;
    }

    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }
}
