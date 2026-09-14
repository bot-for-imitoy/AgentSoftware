package com.agent.software.adapters.persistence;

import com.agent.software.ports.TodoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonRepositoriesTest {

    @Test
    void noteRepositoryRoundTrip(@TempDir Path dir) {
        JsonNoteRepository repo = new JsonNoteRepository(dir);
        assertTrue(repo.list("ceo").isEmpty());

        repo.write("ceo", "plan", "step 1");
        assertEquals("step 1", repo.read("ceo", "plan").orElseThrow());
        assertEquals(List.of("plan"), repo.list("ceo"));

        repo.writeSummary("ceo", 1, "did stuff");
        assertEquals("did stuff", repo.summary("ceo", 1).orElseThrow());
        assertEquals("did stuff", repo.latestSummary("ceo", 2).orElseThrow());
        assertTrue(repo.latestSummary("ceo", 1).isEmpty());

        assertTrue(repo.delete("ceo", "plan"));
        assertTrue(repo.read("ceo", "plan").isEmpty());
    }

    @Test
    void noteRepositoriesAreIsolatedPerRole(@TempDir Path dir) {
        JsonNoteRepository repo = new JsonNoteRepository(dir);
        repo.write("ceo", "a", "1");
        repo.write("coo", "a", "2");
        assertEquals("1", repo.read("ceo", "a").orElseThrow());
        assertEquals("2", repo.read("coo", "a").orElseThrow());
    }

    @Test
    void todoRepositoryRoundTrip(@TempDir Path dir) {
        JsonTodoRepository repo = new JsonTodoRepository(dir);
        TodoRepository.TodoItem item = repo.add("ceo", "write tests", "unit");
        assertEquals("pending", item.status());
        assertEquals(1, repo.list("ceo", null).size());
        assertEquals(1, repo.list("ceo", "pending").size());

        TodoRepository.TodoItem updated = repo.update("ceo", item.id(), "completed").orElseThrow();
        assertEquals("completed", updated.status());
        assertEquals(0, repo.list("ceo", "pending").size());
        assertEquals(1, repo.list("ceo", "completed").size());

        assertTrue(repo.delete("ceo", item.id()));
        assertTrue(repo.list("ceo", null).isEmpty());
    }
}
