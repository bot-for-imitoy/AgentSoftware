package com.agent.software.ports;

import com.agent.software.model.CompanySnapshot;

import java.util.Optional;

/** 快照持久化能力。 */
public interface SnapshotStore {

    Optional<CompanySnapshot> load();

    void save(CompanySnapshot snapshot);
}
