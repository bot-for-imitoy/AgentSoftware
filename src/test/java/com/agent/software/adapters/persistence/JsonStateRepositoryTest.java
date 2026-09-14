package com.agent.software.adapters.persistence;

import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.StateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonStateRepositoryTest {

    private static RoleSpec spec() {
        return new RoleSpec(RoleId.of("ceo"), "Lin Zong", "linzong", 1101, "CEO", "lead", "calm",
                List.of("strategy"), "extra", "Leadership Group", "linzong@company.com",
                "local", Payload.of("base_dir", "/tmp/x"), List.of("note", "talk"));
    }

    @Test
    void roundTripsTheWholeSnapshot(@TempDir Path dir) {
        StateRepository.TaskSnapshot pending = new StateRepository.TaskSnapshot(
                "t1", 6, "leftover work", "test", Payload.of("k", "v"), "pending", "", 0, 1000);
        StateRepository.TaskSnapshot done = new StateRepository.TaskSnapshot(
                "t2", 3, "finished work", "test", Payload.empty(), "done", "ok", 7, 2000);
        StateRepository.Snapshot snapshot = new StateRepository.Snapshot(3, 120, "2026-02-01",
                List.of(new StateRepository.RoleState(spec(), "IDLE", List.of(pending), List.of(done))));

        JsonStateRepository repo = new JsonStateRepository(dir.resolve("state.json"));
        repo.save(snapshot);

        StateRepository.Snapshot loaded = repo.load().orElseThrow();
        assertEquals(3, loaded.day());
        assertEquals(120, loaded.tickOfDay());
        assertEquals("2026-02-01", loaded.baseDate());

        StateRepository.RoleState role = loaded.roles().get(0);
        assertEquals("ceo", role.spec().id().value());
        assertEquals("Lin Zong", role.spec().name());
        assertEquals("linzong@company.com", role.spec().email());
        assertEquals("/tmp/x", role.spec().computerKwargs().str("base_dir", ""));
        assertEquals(List.of("note", "talk"), role.spec().toolkits());
        assertEquals("IDLE", role.state());
        assertEquals("pending", role.pending().get(0).status());
        assertEquals("leftover work", role.pending().get(0).description());
        assertEquals("done", role.history().get(0).status());
        assertEquals(7, role.history().get(0).tokens());
    }

    @Test
    void missingCorruptOrWrongVersionIsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.json");
        JsonStateRepository repo = new JsonStateRepository(file);
        assertTrue(repo.load().isEmpty());

        Files.writeString(file, "not json");
        assertTrue(repo.load().isEmpty());

        Files.writeString(file, "{\"version\":99,\"roles\":[]}");
        assertTrue(repo.load().isEmpty());
    }
}
