package org.javalens.mcp.fixtures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a synthetic 1000-class Maven project on demand for scale-sensitivity
 * tests in the MCP tests fragment.
 *
 * <p>This is a co-located duplicate of
 * {@code org.javalens.core.fixtures.ScaleFixtureGenerator}. OSGi fragments do not
 * share classloaders across host bundles, so each test fragment that needs the
 * scale fixture maintains its own copy. The two copies must stay in lockstep on
 * generated content shape; the {@link ScaleFixtureGeneratorTest} smoke checks
 * in each fragment pin that shape.
 *
 * <p>Fixture content: one {@code com.example.scale.Marker} interface, one
 * {@code com.example.scale.hub.Hub} class declaring {@code public int counter},
 * and {@link #LEAF_CLASS_COUNT} leaf classes split across {@link #PACKAGES}
 * packages. Each leaf implements {@code Marker} and has a {@code touch(Hub)}
 * method that reads and writes {@code Hub.counter}, giving SearchEngine and
 * rename_symbol a meaningful 2000+-reference graph to operate on under JDT
 * index pressure.
 */
public final class ScaleFixtureGenerator {

    public static final int PACKAGES = 100;
    public static final int CLASSES_PER_PACKAGE = 10;
    public static final int LEAF_CLASS_COUNT = PACKAGES * CLASSES_PER_PACKAGE;

    private static volatile Path cached;

    private ScaleFixtureGenerator() {}

    public static synchronized Path getOrCreate() throws IOException {
        if (cached != null && Files.exists(cached)) {
            return cached;
        }
        Path root = Files.createTempDirectory("javalens-scale-");
        writePom(root);
        writeMarkerInterface(root);
        writeHubClass(root);
        writeLeafClasses(root);
        cached = root;
        return root;
    }

    public static Path copyFixtureTo(Path destination) throws IOException {
        Path src = getOrCreate();
        Files.walk(src).forEach(p -> {
            try {
                Path rel = src.relativize(p);
                Path target = destination.resolve(rel);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy " + p, e);
            }
        });
        return destination;
    }

    private static void writePom(Path root) throws IOException {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>scale-maven</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>
                    <properties>
                        <maven.compiler.source>21</maven.compiler.source>
                        <maven.compiler.target>21</maven.compiler.target>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>
                </project>
                """;
        Files.writeString(root.resolve("pom.xml"), pom);
    }

    private static void writeMarkerInterface(Path root) throws IOException {
        Path scaleDir = root.resolve("src/main/java/com/example/scale");
        Files.createDirectories(scaleDir);
        String marker = """
                package com.example.scale;

                public interface Marker {
                }
                """;
        Files.writeString(scaleDir.resolve("Marker.java"), marker);
    }

    private static void writeHubClass(Path root) throws IOException {
        Path hubDir = root.resolve("src/main/java/com/example/scale/hub");
        Files.createDirectories(hubDir);
        String hub = """
                package com.example.scale.hub;

                public class Hub {
                    public int counter;

                    public int read() {
                        return counter;
                    }
                }
                """;
        Files.writeString(hubDir.resolve("Hub.java"), hub);
    }

    private static void writeLeafClasses(Path root) throws IOException {
        for (int p = 0; p < PACKAGES; p++) {
            String pkgName = packageName(p);
            Path pkgDir = root.resolve("src/main/java/com/example/scale/" + pkgName);
            Files.createDirectories(pkgDir);
            for (int c = 0; c < CLASSES_PER_PACKAGE; c++) {
                Files.writeString(pkgDir.resolve("Class" + c + ".java"), leafSource(p, c));
            }
        }
    }

    private static String packageName(int packageIdx) {
        return String.format("pkg%03d", packageIdx);
    }

    private static String leafSource(int packageIdx, int classIdx) {
        String pkg = packageName(packageIdx);
        return """
                package com.example.scale.%s;

                import com.example.scale.Marker;
                import com.example.scale.hub.Hub;

                public class Class%d implements Marker {
                    public void touch(Hub h) {
                        h.counter = h.counter + 1;
                    }
                }
                """.formatted(pkg, classIdx);
    }
}
