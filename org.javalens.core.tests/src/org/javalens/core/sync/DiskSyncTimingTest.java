package org.javalens.core.sync;

import org.javalens.core.fixtures.ScaleFixtureGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the strict-mode detection constant at project scale: the cost a
 * single query pays for stampAll (load-time), verify with no changes (the
 * common per-query case), and verify with one edit. Timings are LOGGED, not
 * asserted — environments vary; the numbers feed the CHANGELOG and #26 notes.
 */
class DiskSyncTimingTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("measurement: detection cost at ~1k files (generated scale fixture)")
    void timing_oneThousandFiles() throws IOException {
        Path project = ScaleFixtureGenerator.copyFixtureTo(tempDir.resolve("scale-1k"));
        measure("scale-1k (generated)", project.resolve("src/main/java"));
    }

    @Test
    @DisplayName("measurement: detection cost at ~10k files (synthesized)")
    void timing_tenThousandFiles() throws IOException {
        Path root = tempDir.resolve("scale-10k/src/main/java");
        for (int pkg = 0; pkg < 100; pkg++) {
            Path dir = root.resolve("com/example/big/p" + pkg);
            Files.createDirectories(dir);
            for (int cls = 0; cls < 100; cls++) {
                Files.writeString(dir.resolve("C" + cls + ".java"),
                    "package com.example.big.p" + pkg + ";\n\npublic class C" + cls + " {\n"
                        + "    public int value() {\n        return " + cls + ";\n    }\n}\n");
            }
        }
        measure("scale-10k (synthesized)", root);
    }

    private void measure(String label, Path sourceRoot) throws IOException {
        DiskStampService service = new DiskStampService(List.of(sourceRoot), List.of());

        long t0 = System.nanoTime();
        service.stampAll();
        long stampMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        DiskStampService.ChangeSet noChange = service.verify();
        long verifyNoChangeMs = (System.nanoTime() - t1) / 1_000_000;
        assertTrue(noChange.isEmpty());

        // Single edit: the typical agent-loop case.
        Path victim;
        try (var walk = Files.walk(sourceRoot)) {
            victim = walk.filter(p -> p.toString().endsWith(".java")).findFirst().orElseThrow();
        }
        Files.writeString(victim, Files.readString(victim) + "// edited\n");

        long t2 = System.nanoTime();
        DiskStampService.ChangeSet oneEdit = service.verify();
        long verifyOneEditMs = (System.nanoTime() - t2) / 1_000_000;
        assertTrue(oneEdit.edited().size() == 1);

        System.out.printf(
            "[disk-sync timing] %s: files=%d stampAll=%dms verify(no-change)=%dms verify(1 edit)=%dms%n",
            label, service.stampedFileCount(), stampMs, verifyNoChangeMs, verifyOneEditMs);
    }
}
