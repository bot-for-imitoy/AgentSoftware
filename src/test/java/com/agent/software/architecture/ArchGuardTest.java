package com.agent.software.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the dependency direction of the new layered packages.
 *
 * <p>Dependencies must point inward only:
 * {@code app -> adapters -> runtime -> ports -> domain -> kernel}. The legacy
 * packages ({@code role}, {@code core}, {@code event}, {@code store}, ...) are
 * being replaced and must never be imported from the new layers.
 */
class ArchGuardTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/agent/software");

    /** For each new package, the top-level {@code com.agent.software.*} packages it may import. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "kernel", Set.of("kernel"),
            "config", Set.of("kernel", "config", "utils"),
            "domain", Set.of("kernel", "domain"),
            "ports", Set.of("kernel", "domain", "ports"),
            "runtime", Set.of("kernel", "domain", "ports", "runtime"));

    @Test
    void newLayersOnlyDependOnAllowedPackages() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : ALLOWED.entrySet()) {
            Path dir = SOURCE_ROOT.resolve(entry.getKey());
            assertTrue(Files.isDirectory(dir), "expected new package to exist: " + dir);
            for (Path file : javaFiles(dir)) {
                for (String imported : importsOf(file)) {
                    if (!imported.startsWith("com.agent.software.")) {
                        continue;
                    }
                    String rest = imported.substring("com.agent.software.".length());
                    String top = rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;
                    if (!entry.getValue().contains(top)) {
                        violations.add(file + " (package " + entry.getKey() + ") -> " + imported);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "dependency-direction violations in new layers:\n" + String.join("\n", violations));
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static List<String> importsOf(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String t = line.strip();
            if (!t.startsWith("import ")) {
                continue;
            }
            String imp = t.substring("import ".length()).replace(";", "").strip();
            if (imp.startsWith("static ")) {
                imp = imp.substring("static ".length()).strip();
            }
            out.add(imp);
        }
        return out;
    }
}
