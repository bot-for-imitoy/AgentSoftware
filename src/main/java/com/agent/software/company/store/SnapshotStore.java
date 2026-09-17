package com.agent.software.company.store;

import java.util.Optional;

/** 快照持久化能力。 */
public interface SnapshotStore {

    Optional<CompanySnapshot> load();

    void save(CompanySnapshot snapshot);
}
