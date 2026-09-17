package com.agent.software.company.store;

import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JsonCodec;

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
