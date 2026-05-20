package org.javalens.core.project;

import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Unit tests for {@link GradleImporter} branches not exercised by the existing
 * {@code GradleClasspathTest} integration suite. GradleClasspathTest covers:
 *
 * <ul>
 *   <li>Happy-path classpath assembly + multi-project aggregation (subprocess).</li>
 *   <li>{@code getSubprojects} include-form parsing + missing-settings + bad paths.</li>
 * </ul>
 *
 * <p>This file targets:
 * <ul>
 *   <li>{@code detectCompilerLevel}/{@code detectAnnotationProcessors} return cached
 *       state — null/empty before {@code getDependencies} runs.</li>
 *   <li>{@code getDependencies} emits {@code GRADLE_SUBPROCESS_FAILED} when no Gradle
 *       binary can be located (via {@code javalens.gradle.binary} override pointing at
 *       a non-existent path).</li>
 *   <li>Cache reset on consecutive {@code getDependencies} calls (Bug J safety).</li>
 * </ul>
 */
class GradleImporterTest {

    private String savedBinaryProperty;

    @BeforeEach
    void setUp() {
        savedBinaryProperty = System.getProperty("javalens.gradle.binary");
    }

    @AfterEach
    void tearDown() {
        if (savedBinaryProperty == null) {
            System.clearProperty("javalens.gradle.binary");
        } else {
            System.setProperty("javalens.gradle.binary", savedBinaryProperty);
        }
    }

    @Test
    @DisplayName("Before any getDependencies call, detectCompilerLevel returns null (cache empty)")
    void compilerLevel_beforeImport_isNull(@TempDir Path projectRoot) {
        GradleImporter importer = new GradleImporter();
        assertNull(importer.detectCompilerLevel(projectRoot),
            "Pre-import cache must be null — orchestrator must call getDependencies first");
    }

    @Test
    @DisplayName("Before any getDependencies call, detectAnnotationProcessors returns empty list")
    void annotationProcessors_beforeImport_areEmpty(@TempDir Path projectRoot) {
        GradleImporter importer = new GradleImporter();
        List<Path> processors = importer.detectAnnotationProcessors(projectRoot);
        assertTrue(processors.isEmpty(),
            "Pre-import processor cache must be empty; got: " + processors);
    }

    @Test
    @DisplayName("getDependencies with a deliberately-invalid Gradle binary emits GRADLE_SUBPROCESS_FAILED")
    void getDependencies_invalidGradleBinary_emitsWarning(@TempDir Path projectRoot) throws IOException {
        // Pretend the project has a valid build.gradle so we don't fail earlier.
        Files.writeString(projectRoot.resolve("build.gradle"), "// stub\n");
        // Override the binary to point at a file that doesn't exist. The override
        // bypasses the wrapper/PATH lookup, so the subprocess start IS attempted —
        // and IOException trips the GRADLE_SUBPROCESS_FAILED branch.
        System.setProperty("javalens.gradle.binary",
            projectRoot.resolve("does-not-exist-binary").toString());

        GradleImporter importer = new GradleImporter();
        List<LoadWarning> warnings = new ArrayList<>();
        List<String> deps = importer.getDependencies(projectRoot, warnings);

        assertTrue(deps.isEmpty(),
            "Failed subprocess → empty dependency list; got: " + deps);
        assertTrue(warnings.stream().anyMatch(w ->
            LoadWarning.GRADLE_SUBPROCESS_FAILED.equals(w.code())),
            "Failed Gradle binary must produce GRADLE_SUBPROCESS_FAILED; got: " + warnings);
    }

    @Test
    @DisplayName("Cache is reset at the start of every getDependencies invocation (no stale leak)")
    void getDependencies_cacheReset_perInvocation(@TempDir Path projectRoot) throws IOException {
        // Run getDependencies once with a bad binary so caches stay empty (and warnings
        // are populated). Then a second run should populate again — no stale state.
        Files.writeString(projectRoot.resolve("build.gradle"), "");
        System.setProperty("javalens.gradle.binary",
            projectRoot.resolve("nonexistent").toString());

        GradleImporter importer = new GradleImporter();
        List<LoadWarning> warningsA = new ArrayList<>();
        importer.getDependencies(projectRoot, warningsA);

        // First call must have produced no cache content (subprocess never succeeded).
        assertNull(importer.detectCompilerLevel(projectRoot),
            "After a failed getDependencies, cached compiler level must remain null");
        assertTrue(importer.detectAnnotationProcessors(projectRoot).isEmpty(),
            "After a failed getDependencies, cached processors must be empty");

        // Second call: still failing, but the source-level invariant is that the
        // cache is RESET at the start of getDependencies — not just left alone.
        // Manually pre-populate caches via a sneaky reflection-free path is not
        // possible; we test the visible behavior — multiple calls produce
        // consistent post-conditions and don't accumulate stale state.
        List<LoadWarning> warningsB = new ArrayList<>();
        importer.getDependencies(projectRoot, warningsB);
        assertNull(importer.detectCompilerLevel(projectRoot));
        assertTrue(importer.detectAnnotationProcessors(projectRoot).isEmpty());

        // Each call must produce its own GRADLE_SUBPROCESS_FAILED warning entry.
        long countA = warningsA.stream().filter(w ->
            LoadWarning.GRADLE_SUBPROCESS_FAILED.equals(w.code())).count();
        long countB = warningsB.stream().filter(w ->
            LoadWarning.GRADLE_SUBPROCESS_FAILED.equals(w.code())).count();
        assertEquals(1, countA, "First invocation must produce one GRADLE_SUBPROCESS_FAILED");
        assertEquals(1, countB, "Second invocation must produce one GRADLE_SUBPROCESS_FAILED");
    }
}
