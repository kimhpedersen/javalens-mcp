package org.javalens.core.buildsystem;

import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BAZEL_NOT_BUILT regression coverage. Two distinct cases must both surface the warning:
 *
 * <ol>
 *   <li><b>No scan roots</b> — neither {@code bazel-bin} nor {@code bazel-out} exists. The
 *       original implementation handled this.</li>
 *   <li><b>Roots present but empty</b> — typically after {@code bazel clean}, the symlinks
 *       survive but point at empty trees. Without this case the warning silently misses
 *       a real "I read what was there and got nothing" scenario.</li>
 * </ol>
 */
class BazelNotBuiltWarningTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("BAZEL_NOT_BUILT fires when neither bazel-bin nor bazel-out exists")
    void warnsWhenNoBazelOutputExists() throws Exception {
        // Build a minimal Bazel workspace with no build outputs at all. detectBuildSystem
        // sees MODULE.bazel and routes the load through the Bazel path; getBazelDependencies
        // finds no scan roots and surfaces the warning.
        Path projectRoot = helper.getTempDirectory().resolve("bazel-no-outputs");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("MODULE.bazel"), "module(name = \"empty\")\n");

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);

        List<LoadWarning> warnings = service.getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> LoadWarning.BAZEL_NOT_BUILT.equals(w.code())),
            "Expected BAZEL_NOT_BUILT when no bazel-bin/bazel-out exists. Got: " + warnings);
    }

    @Test
    @DisplayName("BAZEL_NOT_BUILT fires when bazel-bin exists but contains no jars")
    void warnsWhenBazelOutputIsEmpty() throws Exception {
        // Stand in for the post-`bazel clean` state: directories are present but empty.
        Path projectRoot = helper.getTempDirectory().resolve("bazel-empty-outputs");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("MODULE.bazel"), "module(name = \"empty\")\n");
        Files.createDirectories(projectRoot.resolve("bazel-bin"));
        Files.createDirectories(projectRoot.resolve("bazel-out"));

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);

        List<LoadWarning> warnings = service.getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> LoadWarning.BAZEL_NOT_BUILT.equals(w.code())),
            "Expected BAZEL_NOT_BUILT when bazel-bin/bazel-out exist but contain no jars. " +
            "Got: " + warnings);
    }
}
