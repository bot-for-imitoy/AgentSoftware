package com.agent.software.utils;

import java.util.UUID;

/**
 * 带 UUID 身份的基类：一切需要全局唯一标识、需要跨引用或需要持久化的实体都继承它。
 *
 * <p>身份按 {@code uuid} 判定，因此 {@link #equals(Object)} / {@link #hashCode()} 只使用 uuid。
 * 不这样做的话，{@code UUIDObjectManager.contains/remove} 和集合去重都会失效。
 */
public abstract class UUIDObject {

    /**
     * 身份。为了支持 {@link Data#loadData(Map)} 回填（见报备项 B1），这里不能声明成 final：
     * 持久化恢复是"先造空实例、再回填字段"的流程，final 字段回填不了。
     */
    public String uuid;

    public UUIDObject() {
        this.uuid = UUID.randomUUID().toString();
    }

    public UUIDObject(String uuid) {
        this.uuid = uuid == null ? UUID.randomUUID().toString() : uuid;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UUIDObject other)) {
            return false;
        }
        return uuid.equals(other.uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + uuid + ")";
    }
}
