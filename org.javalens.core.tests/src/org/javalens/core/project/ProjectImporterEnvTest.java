package org.javalens.core.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code ProjectImporter.propagateJavaHome} hands the running JVM's location
 * to subprocess children. On Windows GitHub Actions runners, Tycho's surefire fork
 * can sanitize the environment of the test JVM such that {@code JAVA_HOME} is unset.
 * When the test JVM then spawns {@code mvn.cmd} or {@code gradle.bat} via
 * {@code ProcessBuilder}, those scripts fall through to a PATH-based {@code java.exe}
 * lookup, picking up the wrong JDK and tripping the launcher's {@code lib/jvm.cfg}
 * read. Explicit propagation closes that hole.
 */
class ProjectImporterEnvTest {

    /**
     * The expected JAVA_HOME the helper resolves, accounting for the
     * {@code JAVALENS_TESTS_CHILD_JAVA_HOME} override path used in CI.
     */
    private static String expectedJavaHome() {
        String override = System.getenv("JAVALENS_TESTS_CHILD_JAVA_HOME");
        if (override != null) override = override.trim();
        return (override != null && !override.isBlank())
            ? override : System.getProperty("java.home").trim();
    }

    @Test
    @DisplayName("propagateJavaHome sets JAVA_HOME from the running JVM (or the test override)")
    void setsJavaHomeFromRunningJvm() {
        ProjectImporter importer = new ProjectImporter();
        ProcessBuilder pb = new ProcessBuilder("dummy");
        importer.propagateJavaHome(pb);

        assertEquals(expectedJavaHome(), pb.environment().get("JAVA_HOME"),
            "JAVA_HOME on the child env must equal the resolved parent JVM home (or the " +
            "JAVALENS_TESTS_CHILD_JAVA_HOME override)");
    }

    @Test
    @DisplayName("propagateJavaHome prepends the resolved java.home/bin to the child PATH")
    void prependsJavaBinToPath() {
        ProjectImporter importer = new ProjectImporter();
        ProcessBuilder pb = new ProcessBuilder("dummy");
        importer.propagateJavaHome(pb);

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String pathKey = isWindows ? "Path" : "PATH";
        String path = pb.environment().get(pathKey);

        String expectedPrefix = expectedJavaHome() + File.separator + "bin";
        assertTrue(path != null && path.startsWith(expectedPrefix),
            "Child PATH must begin with the resolved JDK's bin. Got prefix: " +
            (path == null ? "null" : path.substring(0, Math.min(150, path.length()))));
    }

    @Test
    @DisplayName("propagateJavaHome preserves the existing PATH after the prepended bin")
    void preservesExistingPath() {
        ProjectImporter importer = new ProjectImporter();
        ProcessBuilder pb = new ProcessBuilder("dummy");
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String pathKey = isWindows ? "Path" : "PATH";
        String sentinel = "/javalens-test-sentinel-segment";
        pb.environment().put(pathKey, sentinel);

        importer.propagateJavaHome(pb);

        String path = pb.environment().get(pathKey);
        assertTrue(path != null && path.endsWith(sentinel),
            "Existing PATH entries must remain after the prepended JDK bin so other " +
            "tools the user expects to be available stay reachable. Got: " + path);
    }
}
