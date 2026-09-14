package com.agent.software.adapters.computer;

import com.agent.software.computers.ComputerManager;
import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ComputerPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyComputerAdapterTest {

    private static RoleSpec localSpec(Path dir) {
        Map<String, Object> kwargs = Map.of(
                "base_dir", dir.resolve("computers").toString(),
                "drive_dir", dir.resolve("drive").toString());
        return new RoleSpec(RoleId.of("ceo"), "Lin Zong", "linzong", 1101, "CEO", "", "",
                List.of(), "", "Leadership Group", "", "local", Payload.of(kwargs), List.of());
    }

    @Test
    void localComputerRoundTripThroughThePort(@TempDir Path dir) throws Exception {
        ComputerPort computer = ComputerAdapters.open(new ComputerManager(), localSpec(dir));

        assertTrue(computer.isOn());
        assertTrue(computer.describe().contains("ceo"));

        computer.writeFile("notes/hello.txt", "hi there");
        assertEquals("hi there", computer.readFile("notes/hello.txt").orElseThrow());
        assertTrue(computer.listDir("notes").contains("hello.txt"));
        assertTrue(Files.exists(dir.resolve("computers/ceo/notes/hello.txt")));

        ComputerPort.ExecResult exec = computer.exec("echo hi", Duration.ofSeconds(10), 2000);
        assertTrue(exec.ok());
        assertTrue(exec.output().contains("hi"));

        assertTrue(computer.deleteFile("notes/hello.txt").contains("Deleted"));
        assertTrue(computer.readFile("notes/hello.txt").isEmpty());
        assertTrue(computer.mcpTools().isEmpty());
    }

    @Test
    void missingFileIsReportedAsEmpty(@TempDir Path dir) {
        ComputerPort computer = ComputerAdapters.open(new ComputerManager(), localSpec(dir));
        assertTrue(computer.readFile("nope.txt").isEmpty());
    }

    @Test
    void exitMarkerIsParsed() {
        assertEquals(3, LegacyComputerAdapter.parseExit("[exit 3] something failed"));
        assertEquals(0, LegacyComputerAdapter.parseExit("plain output"));
        assertEquals(0, LegacyComputerAdapter.parseExit(null));
    }
}
