package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.Json;
import com.maf.scheduler.core.TodoStore;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.core.ToolRegistry.ToolHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Todo 清单工具类 (Todo ToolKit) — Python 版 todo_toolkit.py.
 *
 * 包含: todo_add / todo_list / todo_update / todo_delete.
 */
public final class TodoToolkit {

    private TodoToolkit() {
    }

    /** 创建 Todo 清单工具类. */
    public static ToolKit createTodoToolkit() {
        ToolKit tk = new ToolKit("todo", "Todo 清单工具类: 管理自己的待办事项");

        ToolHandler todoAdd = args -> {
            String title = Json.str(args, "title", "").strip();
            String detail = Json.str(args, "detail", "").strip();
            if (title.isEmpty()) {
                return "错误: 'title' (待办标题) 为必填参数.";
            }
            Map<String, Object> item = store(tk).add(title, detail);
            return "已添加待办 [ID=" + item.get("id") + "]: " + item.get("title") + " (状态 pending)";
        };

        ToolHandler todoList = args -> {
            Object statusObj = args.get("status");
            String status = statusObj instanceof String s && !s.strip().isEmpty() ? s.strip() : null;
            List<Map<String, Object>> items = store(tk).list(status);
            if (items.isEmpty()) {
                String hint = status != null ? " (状态=" + status + ")" : "";
                return "(暂无待办" + hint + ")";
            }
            List<String> lines = new ArrayList<>();
            for (Map<String, Object> i : items) {
                lines.add("- " + fmt(i));
            }
            String head = "待办清单 (" + items.size() + " 项";
            if (status != null) {
                head += ", 状态=" + status;
            }
            return head + "):\n" + String.join("\n", lines);
        };

        ToolHandler todoUpdate = args -> {
            String todoId = Json.str(args, "todo_id", "").strip();
            String status = Json.str(args, "status", "").strip();
            if (todoId.isEmpty()) {
                return "错误: 'todo_id' (待办 id) 为必填参数.";
            }
            if (status.isEmpty()) {
                return "错误: 'status' (新状态) 为必填参数.";
            }
            Map<String, Object> item;
            try {
                item = store(tk).update(todoId, status);
            } catch (IllegalArgumentException exc) {
                return "错误: " + exc.getMessage();
            }
            if (item == null) {
                return "待办不存在: " + todoId;
            }
            return "待办已更新 [ID=" + todoId + "]: " + item.get("title") + " → " + item.get("status");
        };

        ToolHandler todoDelete = args -> {
            String todoId = Json.str(args, "todo_id", "").strip();
            if (todoId.isEmpty()) {
                return "错误: 'todo_id' (待办 id) 为必填参数.";
            }
            if (store(tk).delete(todoId)) {
                return "待办已删除: " + todoId;
            }
            return "待办不存在: " + todoId;
        };

        Map<String, Object> addSchema = new LinkedHashMap<>();
        addSchema.put("type", "object");
        addSchema.put("properties", Map.of(
                "title", TalkToolkit.mapOf("string", "待办标题"),
                "detail", TalkToolkit.mapOf("string", "待办详情 (可选)")));
        addSchema.put("required", List.of("title"));

        Map<String, Object> listSchema = new LinkedHashMap<>();
        listSchema.put("type", "object");
        listSchema.put("properties", Map.of(
                "status", TalkToolkit.mapOf("string", "状态过滤 (可选)")));

        Map<String, Object> updateSchema = new LinkedHashMap<>();
        updateSchema.put("type", "object");
        updateSchema.put("properties", Map.of(
                "todo_id", TalkToolkit.mapOf("string", "待办 id (从 todo_list 获取)"),
                "status", TalkToolkit.mapOf("string", "新状态: pending / in_progress / completed")));
        updateSchema.put("required", List.of("todo_id", "status"));

        Map<String, Object> deleteSchema = new LinkedHashMap<>();
        deleteSchema.put("type", "object");
        deleteSchema.put("properties", Map.of(
                "todo_id", TalkToolkit.mapOf("string", "待办 id (从 todo_list 获取)")));
        deleteSchema.put("required", List.of("todo_id"));

        tk.addPythonTool("todo_add",
                "添加一条待办事项到我的 Todo 清单 (返回待办 id, 后续用它更新/删除).",
                addSchema, todoAdd);
        tk.addPythonTool("todo_list",
                "列出我的 Todo 待办清单 (可按状态过滤: pending/in_progress/completed).",
                listSchema, todoList);
        tk.addPythonTool("todo_update",
                "更新一条待办的状态: pending(未开始) → in_progress(进行中) → completed(已完成).",
                updateSchema, todoUpdate);
        tk.addPythonTool("todo_delete",
                "删除一条待办事项 (从清单移除).",
                deleteSchema, todoDelete);
        return tk;
    }

    /** 条目 → 展示文本. */
    private static String fmt(Map<String, Object> item) {
        String mark = switch (String.valueOf(item.getOrDefault("status", "pending"))) {
            case "in_progress" -> "🔄";
            case "completed" -> "✅";
            default -> "⬜";
        };
        String base = "[" + item.get("id") + "] " + mark + " " + item.getOrDefault("status", "pending")
                + ": " + item.get("title");
        Object detail = item.get("detail");
        if (detail != null && !String.valueOf(detail).isEmpty()) {
            base += " — " + detail;
        }
        return base;
    }

    private static TodoStore store(ToolKit tk) {
        TodoStore store = (TodoStore) tk.get("store", null);
        if (store == null) {
            throw new RuntimeException("Todo 工具类尚未绑定存储, 请通过 role.add_toolkit() 注册");
        }
        return store;
    }

    /** 将 TodoStore 绑定到工具类. */
    public static void bindTodoToToolkit(ToolKit toolkit, TodoStore store) {
        toolkit.bind("store", store);
    }
}
