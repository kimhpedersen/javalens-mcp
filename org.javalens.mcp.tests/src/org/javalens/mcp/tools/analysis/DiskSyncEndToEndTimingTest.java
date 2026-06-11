package org.javalens.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.sync.DiskSyncMode;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.FindReferencesTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tool-call latency under the disk-sync chokepoint (logged, not
 * asserted): manual mode (pre-1.5.0 baseline) vs strict no-change (the
 * common case) vs a strict query that carries a one-file repair (reconcile +
 * index barrier included). Complements DiskSyncTimingTest, which measures
 * the detection layer in isolation.
 */
class DiskSyncEndToEndTimingTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final int WARMUP = 3;
    private static final int RUNS = 10;

    @Test
    @DisplayName("measurement: find_references latency - manual vs strict vs strict+repair")
    void timing_endToEnd() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path projectCopy = service.getProjectRoot();
        FindReferencesTool tool = new FindReferencesTool(() -> service);
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", projectCopy.resolve("src/main/java/com/example/Calculator.java").toString());
        args.put("line", 14);
        args.put("column", 16);

        service.setDiskSyncMode(DiskSyncMode.MANUAL);
        long manualAvg = averageMs(tool, args);

        service.setDiskSyncMode(DiskSyncMode.STRICT);
        long strictAvg = averageMs(tool, args);

        // A repair-carrying query: edit one file, then time the next call.
        Path greeter = projectCopy.resolve("src/main/java/com/example/Greeter.java");
        Files.writeString(greeter, Files.readString(greeter) + "// timing edit\n");
        long t0 = System.nanoTime();
        assertTrue(tool.execute(args).isSuccess());
        long repairMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf(
            "[disk-sync e2e timing] find_references on simple-maven (72 files, avg of %d): "
                + "manual=%dms strict(no-change)=%dms strict(1-file repair)=%dms overhead=%dms%n",
            RUNS, manualAvg, strictAvg, repairMs, strictAvg - manualAvg);
    }

    private long averageMs(FindReferencesTool tool, ObjectNode args) {
        for (int i = 0; i < WARMUP; i++) {
            assertTrue(tool.execute(args).isSuccess());
        }
        long total = 0;
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            assertTrue(tool.execute(args).isSuccess());
            total += (System.nanoTime() - t0) / 1_000_000;
        }
        return total / RUNS;
    }
}
