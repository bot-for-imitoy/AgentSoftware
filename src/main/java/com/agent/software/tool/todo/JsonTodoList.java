package com.agent.software.tool.todo;

import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JsonCodec;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TodoId;
import com.agent.software.tool.todo.Todo.TodoStatus;

import java.util.List;
import java.util.Optional;

/**
 * JSON 文件形态的待办清单：按角色存放，状态迁移显式落盘。
 */
public final class JsonTodoList implements TodoList {

    /** 绑定数据路径与 JSON 编解码器。 */
    public JsonTodoList(AppPaths paths, JsonCodec json) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Todo> list(RoleId owner, TodoStatus filter) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public Todo add(RoleId owner, String title, String detail) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public Optional<Todo> update(RoleId owner, TodoId id, TodoStatus status) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public boolean delete(RoleId owner, TodoId id) {
        throw new UnsupportedOperationException("skeleton");
    }
}
