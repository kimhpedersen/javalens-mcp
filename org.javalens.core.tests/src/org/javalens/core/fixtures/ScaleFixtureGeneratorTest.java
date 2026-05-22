package org.javalens.core.fixtures;

import org.javalens.core.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ScaleFixtureGeneratorTest {

    @Test
    @DisplayName("getOrCreate produces a Maven project with LEAF_CLASS_COUNT leafs + 1 Hub class")
    void getOrCreate_producesExpectedSourceCount() throws IOException {
        Path root = ScaleFixtureGenerator.getOrCreate();

        assertTrue(Files.exists(root), "Fixture root must exist: " + root);
        assertTrue(Files.exists(root.resolve("pom.xml")), "pom.xml must be present");

        Path sourceDir = root.resolve("src/main/java");
        assertTrue(Files.isDirectory(sourceDir), "src/main/java must exist");

        try (Stream<Path> walk = Files.walk(sourceDir)) {
            long javaFileCount = walk.filter(p -> p.toString().endsWith(".java")).count();
            assertEquals(
                ScaleFixtureGenerator.LEAF_CLASS_COUNT + 1,
                javaFileCount,
                "Source-file count must equal LEAF_CLASS_COUNT (1000 leafs) + 1 (Hub)");
        }
    }

    @Test
    @DisplayName("pom declares Java 21 source/target compliance")
    void pom_declaresJava21Compliance() throws IOException {
        Path root = ScaleFixtureGenerator.getOrCreate();
        String pom = Files.readString(root.resolve("pom.xml"));
        assertTrue(pom.contains("<maven.compiler.source>21</maven.compiler.source>"),
            "pom must declare Java 21 source: " + pom);
        assertTrue(pom.contains("<maven.compiler.target>21</maven.compiler.target>"),
            "pom must declare Java 21 target: " + pom);
    }

    @Test
    @DisplayName("hub class is present and declares the counter field referenced by all leaf classes")
    void hubClass_declaresCounterField() throws IOException {
        Path root = ScaleFixtureGenerator.getOrCreate();
        Path hub = root.resolve("src/main/java/com/example/scale/hub/Hub.java");
        assertTrue(Files.exists(hub), "Hub.java must be present at " + hub);
        String src = Files.readString(hub);
        assertTrue(src.contains("public int counter"),
            "Hub must declare `public int counter`; got: " + src);
    }

    @Test
    @DisplayName("each leaf package contains exactly CLASSES_PER_PACKAGE files named Class0..ClassN-1")
    void leafPackages_haveExpectedClassNames() throws IOException {
        Path root = ScaleFixtureGenerator.getOrCreate();
        Path pkgZero = root.resolve("src/main/java/com/example/scale/pkg000");
        assertTrue(Files.isDirectory(pkgZero), "pkg000 must exist");
        try (Stream<Path> entries = Files.list(pkgZero)) {
            long count = entries.filter(p -> p.toString().endsWith(".java")).count();
            assertEquals(ScaleFixtureGenerator.CLASSES_PER_PACKAGE, count,
                "pkg000 must have CLASSES_PER_PACKAGE classes");
        }
        for (int c = 0; c < ScaleFixtureGenerator.CLASSES_PER_PACKAGE; c++) {
            Path classFile = pkgZero.resolve("Class" + c + ".java");
            assertTrue(Files.exists(classFile),
                "pkg000/Class" + c + ".java must exist");
        }
    }

    @Test
    @DisplayName("each leaf class references Hub.counter so rename/find-references at scale has work to do")
    void leafClasses_referenceHubCounter() throws IOException {
        Path root = ScaleFixtureGenerator.getOrCreate();
        Path leaf = root.resolve("src/main/java/com/example/scale/pkg042/Class7.java");
        assertTrue(Files.exists(leaf), "Sample leaf class must exist: " + leaf);
        String src = Files.readString(leaf);
        assertTrue(src.contains("com.example.scale.hub.Hub"),
            "Leaf class must import Hub; got: " + src);
        assertTrue(src.contains(".counter"),
            "Leaf class must reference Hub.counter; got: " + src);
    }

    @Test
    @DisplayName("getOrCreate is idempotent — two calls return the same cached Path")
    void getOrCreate_isCachedAcrossCalls() throws IOException {
        Path first = ScaleFixtureGenerator.getOrCreate();
        Path second = ScaleFixtureGenerator.getOrCreate();
        assertEquals(first, second,
            "Repeated calls must return the same cached Path; got first=" + first + " second=" + second);
    }

    @Test
    @DisplayName("JdtServiceImpl loads the scale fixture cleanly")
    void jdtServiceImpl_loadsScaleFixtureCleanly() throws Exception {
        Path root = ScaleFixtureGenerator.getOrCreate();
        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(root);
        assertNotNull(service.getJavaProject(),
            "JavaProject must be available after loadProject");
        assertTrue(service.getJavaProject().exists(),
            "JavaProject must exist after loadProject");
    }
}
