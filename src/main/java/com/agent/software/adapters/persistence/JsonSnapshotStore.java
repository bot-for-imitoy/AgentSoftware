package com.agent.software.adapters.persistence;

import com.agent.software.adapters.config.AppPaths;
import com.agent.software.model.CompanySnapshot;
import com.agent.software.ports.JsonCodec;
import com.agent.software.ports.SnapshotStore;

import java.util.Optional;

/**
 * JSON 快照存储：原子写 state.json，并在读取时兼容旧档。
 */
public final class JsonSnapshotStore implements SnapshotStore {

    /** 绑定数据路径与 JSON 编解码器。 */
    public JsonSnapshotStore(AppPaths paths, JsonCodec json) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public Optional<CompanySnapshot> load() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void save(CompanySnapshot snapshot) {
        throw new UnsupportedOperationException("skeleton");
    }
}
