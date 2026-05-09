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

    @Test
    @DisplayName("propagateJavaHome sets JAVA_HOME to the running JVM's java.home")
    void setsJavaHomeFromRunningJvm() {
        ProjectImporter importer = new ProjectImporter();
        ProcessBuilder pb = new ProcessBuilder("dummy");
        importer.propagateJavaHome(pb);

        String expected = System.getProperty("java.home");
        assertEquals(expected, pb.environment().get("JAVA_HOME"),
            "JAVA_HOME on the child env must equal the parent JVM's java.home");
    }

    @Test
    @DisplayName("propagateJavaHome prepends java.home/bin to the child PATH")
    void prependsJavaBinToPath() {
        ProjectImporter importer = new ProjectImporter();
        ProcessBuilder pb = new ProcessBuilder("dummy");
        importer.propagateJavaHome(pb);

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String pathKey = isWindows ? "Path" : "PATH";
        String path = pb.environment().get(pathKey);

        String expectedPrefix = System.getProperty("java.home") + File.separator + "bin";
        assertTrue(path != null && path.startsWith(expectedPrefix),
            "Child PATH must begin with the parent JVM's bin so the launcher resolves " +
            "the right java.exe. Got: " + path);
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
