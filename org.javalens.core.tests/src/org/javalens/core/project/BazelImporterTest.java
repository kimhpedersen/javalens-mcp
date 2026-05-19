package org.javalens.core.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BazelImporter} pieces that aren't naturally exercised
 * by the existing {@code EndToEndBazelIntegrationTest} / {@code BazelNotBuiltWarningTest}
 * / {@code BazelSymlinkDedupTest} integration suite:
 *
 * <ul>
 *   <li>{@code detectCompilerLevel} regex variants: paired {@code -source}/{@code -target}/
 *       {@code --release} and the inline {@code --release=N} form, plus picking the
 *       highest level when multiple BUILD files declare different levels.</li>
 *   <li>{@code getTargetPackages} discovery of BUILD-prefixed directories.</li>
 * </ul>
 *
 * <p>The bazel-bin/bazel-out scanning paths require real Bazel output trees and stay
 * with the existing integration tests; this file targets only the pure-function pieces
 * that can be exercised with simple {@code @TempDir} fixtures.
 */
class BazelImporterTest {

    private final BazelImporter importer = new BazelImporter();

    @Test
    @DisplayName("detectCompilerLevel returns null when no BUILD file declares javacopts")
    void detectCompilerLevel_noJavacopts_returnsNull(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("BUILD"),
            "java_library(name = \"lib\", srcs = [\"X.java\"])\n");
        assertNull(importer.detectCompilerLevel(projectRoot),
            "BUILD without javacopts must yield null (signals 'use JVM default')");
    }

    @Test
    @DisplayName("detectCompilerLevel parses paired `-source` / `-target` form")
    void detectCompilerLevel_pairedSourceTarget() throws IOException {
        Path projectRoot = Files.createTempDirectory("bazel-pair");
        try {
            Files.writeString(projectRoot.resolve("BUILD"),
                "java_library(\n"
                    + "    name = \"lib\",\n"
                    + "    srcs = [\"X.java\"],\n"
                    + "    javacopts = [\"-source\", \"17\", \"-target\", \"17\"],\n"
                    + ")\n");
            assertEquals("17", importer.detectCompilerLevel(projectRoot),
                "Paired `-source` `-target` 17 must produce level 17");
        } finally {
            Files.deleteIfExists(projectRoot.resolve("BUILD"));
            Files.deleteIfExists(projectRoot);
        }
    }

    @Test
    @DisplayName("detectCompilerLevel parses paired `--release` form")
    void detectCompilerLevel_pairedReleaseForm(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("BUILD.bazel"),
            "java_library(\n"
                + "    name = \"lib\",\n"
                + "    srcs = [\"X.java\"],\n"
                + "    javacopts = [\"--release\", \"21\"],\n"
                + ")\n");
        assertEquals("21", importer.detectCompilerLevel(projectRoot),
            "Paired `--release` 21 must produce level 21");
    }

    @Test
    @DisplayName("detectCompilerLevel parses inline `--release=N` form")
    void detectCompilerLevel_inlineReleaseForm(@TempDir Path projectRoot) throws IOException {
        // The inline `--release=17` token is its own regex branch in
        // highestLevelInJavacopts — distinct from the paired pattern.
        Files.writeString(projectRoot.resolve("BUILD"),
            "java_binary(\n"
                + "    name = \"bin\",\n"
                + "    srcs = [\"Main.java\"],\n"
                + "    javacopts = [\"--release=17\", \"-Xlint:all\"],\n"
                + ")\n");
        assertEquals("17", importer.detectCompilerLevel(projectRoot),
            "Inline `--release=17` must produce level 17");
    }

    @Test
    @DisplayName("detectCompilerLevel returns highest level across multiple BUILD files (multi-target)")
    void detectCompilerLevel_picksHighestAcrossFiles(@TempDir Path projectRoot) throws IOException {
        // Create two packages: one declares Java 11, the other Java 17. The importer
        // must surface the highest (17) — multi-target Bazel projects commonly have
        // mixed levels and the orchestrator wants the maximum so all targets compile.
        Files.createDirectories(projectRoot.resolve("a"));
        Files.writeString(projectRoot.resolve("a/BUILD"),
            "java_library(name = \"a\", srcs = [\"A.java\"], javacopts = [\"-source\", \"11\"])\n");
        Files.createDirectories(projectRoot.resolve("b"));
        Files.writeString(projectRoot.resolve("b/BUILD"),
            "java_library(name = \"b\", srcs = [\"B.java\"], javacopts = [\"-source\", \"17\"])\n");

        assertEquals("17", importer.detectCompilerLevel(projectRoot),
            "Multi-file detection must pick the highest declared level; got: "
                + importer.detectCompilerLevel(projectRoot));
    }

    @Test
    @DisplayName("getTargetPackages discovers directories containing BUILD or BUILD.bazel")
    void getTargetPackages_discoversBuildDirectories(@TempDir Path projectRoot) throws IOException {
        Files.createDirectories(projectRoot.resolve("pkg1"));
        Files.writeString(projectRoot.resolve("pkg1/BUILD"), "");
        Files.createDirectories(projectRoot.resolve("pkg2"));
        Files.writeString(projectRoot.resolve("pkg2/BUILD.bazel"), "");
        Files.createDirectories(projectRoot.resolve("not-a-pkg"));
        // not-a-pkg has no BUILD file → must be excluded

        List<Path> packages = importer.getTargetPackages(projectRoot);
        assertTrue(packages.contains(projectRoot.resolve("pkg1")),
            "pkg1 with BUILD must be discovered; got: " + packages);
        assertTrue(packages.contains(projectRoot.resolve("pkg2")),
            "pkg2 with BUILD.bazel must be discovered; got: " + packages);
        assertTrue(packages.stream().noneMatch(p -> p.endsWith("not-a-pkg")),
            "not-a-pkg (no BUILD file) must NOT be discovered; got: " + packages);
    }

    @Test
    @DisplayName("getTargetPackages skips bazel-* output directories")
    void getTargetPackages_skipsBazelOutputDirs(@TempDir Path projectRoot) throws IOException {
        // bazel-bin commonly exists as a symlink with deeply-nested BUILD files
        // (from the build's own derived packages). The importer must not surface
        // those as user-facing packages.
        Files.createDirectories(projectRoot.resolve("bazel-bin/nested"));
        Files.writeString(projectRoot.resolve("bazel-bin/nested/BUILD"), "");
        Files.createDirectories(projectRoot.resolve("real-pkg"));
        Files.writeString(projectRoot.resolve("real-pkg/BUILD"), "");

        List<Path> packages = importer.getTargetPackages(projectRoot);
        assertTrue(packages.contains(projectRoot.resolve("real-pkg")));
        assertTrue(packages.stream().noneMatch(p -> p.toString().contains("bazel-bin")),
            "Directories under bazel-bin/ must be skipped; got: " + packages);
    }

    @Test
    @DisplayName("getDependencies emits BAZEL_NOT_BUILT warning when neither bazel-bin nor bazel-out exists")
    void getDependencies_noBazelOutputDirs_emitsWarning(@TempDir Path projectRoot) {
        List<org.javalens.core.project.model.LoadWarning> warnings = new ArrayList<>();
        List<String> deps = importer.getDependencies(projectRoot, warnings);
        assertTrue(deps.isEmpty(),
            "No bazel-bin/bazel-out → empty dependency list; got: " + deps);
        assertTrue(warnings.stream().anyMatch(w ->
            org.javalens.core.project.model.LoadWarning.BAZEL_NOT_BUILT.equals(w.code())),
            "Must emit BAZEL_NOT_BUILT warning when no output dirs exist; got: " + warnings);
    }

    @Test
    @DisplayName("getDependencies does not duplicate BAZEL_NOT_BUILT when one already exists in warnings")
    void getDependencies_warningDeduplicated(@TempDir Path projectRoot) {
        // The importer dedups its own warning emission against any prior pass that
        // already added BAZEL_NOT_BUILT — useful for multi-module orchestration where
        // multiple roots all fail the bazel-built check.
        List<org.javalens.core.project.model.LoadWarning> warnings = new ArrayList<>();
        warnings.add(new org.javalens.core.project.model.LoadWarning(
            org.javalens.core.project.model.LoadWarning.BAZEL_NOT_BUILT,
            "Prior warning",
            "Hint",
            null));
        importer.getDependencies(projectRoot, warnings);

        long count = warnings.stream().filter(w ->
            org.javalens.core.project.model.LoadWarning.BAZEL_NOT_BUILT.equals(w.code())).count();
        assertEquals(1, count,
            "BAZEL_NOT_BUILT warning must not be duplicated when one already exists; got: " + count);
    }
}
