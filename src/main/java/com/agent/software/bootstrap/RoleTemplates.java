package com.agent.software.bootstrap;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.infra.json.JsonCodec;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色模板加载：classpath 的 {@code role_templates.json} → {@link RoleSpec} 列表。
 *
 * <p>对应 master {@code role.RoleLoader}（386 行）。这里只保留"读模板"这件事：
 * 模板文件的形状与 master 完全一致（扁平对象 {@code {role_id: {...}}}），
 * 因此可以直接复用同一份资源文件。
 *
 * <p>与 master 的两点差异（都记在 PLAN §11）：
 * <ul>
 *   <li>不再做"姓名 → 拼音用户名"的推导：模板里本来就有 {@code username}；
 *       缺失时退回 {@code role_id}。</li>
 *   <li>{@code uid} 缺失时按 {@code 1100 + 序号} 分配（对齐 master 的容器 uid 约定）。</li>
 * </ul>
 */
final class RoleTemplates {

    private static final Logger logger = LoggerFactory.getLogger(RoleTemplates.class);

    /** classpath 资源名。 */
    static final String RESOURCE = "role_templates.json";

    /** 容器 uid 起始值（对齐 master）。 */
    private static final int UID_BASE = 1100;

    /** 领导组：成员额外获得 talk_to_client 工具（对齐 master {@code Toolkits.LEADERSHIP_GROUP}）。 */
    static final String LEADERSHIP_GROUP = "Leadership Group";

    private RoleTemplates() {
    }

    /** 读取全部模板（按字段名排序，保证启动顺序稳定）。 */
    static List<RoleSpec> load(JsonCodec json) {
        Map<String, Object> root = readResource(json);
        List<RoleSpec> out = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, Object> entry : new java.util.TreeMap<>(root).entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) raw;
            index++;
            try {
                out.add(toSpec(entry.getKey(), fields, index));
            } catch (RuntimeException e) {
                logger.warn("角色模板 {} 解析失败，已跳过：{}", entry.getKey(), e.getMessage());
            }
        }
        logger.info("已加载 {} 个角色模板", out.size());
        return out;
    }

    /** 按 role_id 取一个模板的新副本。 */
    static List<RoleSpec> withIds(List<RoleSpec> templates, Set<String> ids) {
        List<RoleSpec> out = new ArrayList<>();
        for (RoleSpec spec : templates) {
            if (ids == null || ids.isEmpty() || ids.contains(spec.id().value())) {
                out.add(spec);
            }
        }
        return out;
    }

    private static Map<String, Object> readResource(JsonCodec json) {
        try (InputStream in = RoleTemplates.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                logger.warn("找不到角色模板资源 {}，默认团队为空", RESOURCE);
                return Map.of();
            }
            return json.readMap(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException | RuntimeException e) {
            logger.error("读取角色模板资源失败：{}", e.getMessage());
            return Map.of();
        }
    }

    private static RoleSpec toSpec(String roleId, Map<String, Object> m, int index) {
        String name = str(m, "name", roleId);
        String group = str(m, "group", "");
        Set<String> toolkits = new LinkedHashSet<>(RoleSpec.DEFAULT_TOOLKITS);
        if (LEADERSHIP_GROUP.equals(group)) {
            toolkits.add("client");
        }

        Map<String, String> computerOptions = new LinkedHashMap<>();
        Object kwargs = m.get("computer_kwargs");
        if (kwargs instanceof Map<?, ?> kw) {
            for (Map.Entry<?, ?> e : kw.entrySet()) {
                computerOptions.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        String computerKind = str(m, "computer_kind", "local");

        return RoleSpec.builder()
                .id(new RoleId(roleId))
                .name(name)
                .username(str(m, "username", roleId))
                .uid(intVal(m, "uid", UID_BASE + index))
                .title(str(m, "title", ""))
                .responsibilities(str(m, "responsibilities", ""))
                .personality(str(m, "personality", ""))
                .skills(strList(m, "skills"))
                .group(group)
                .email(str(m, "email", ""))
                .promptExtra(str(m, "system_prompt_extra", ""))
                .interestKeywords(new LinkedHashSet<>(strList(m, "interest_keywords")))
                .salienceThreshold(doubleVal(m, "salience_threshold", RoleSpec.DEFAULT_SALIENCE_THRESHOLD))
                .computer(new RoleSpec.ComputerSpec(computerKind, computerOptions))
                .toolkits(toolkits)
                .defaultRole(boolVal(m, "is_default", false))
                .build();
    }

    // ── 取值助手 ───────────────────────────────────────────────

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return v == null ? def : def;
    }

    private static double doubleVal(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static boolean boolVal(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v));
    }

    private static List<String> strList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !Text.isBlank(String.valueOf(o))) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }
}
