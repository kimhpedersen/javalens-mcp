package org.javalens.core.fixtures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a synthetic 1000-class Maven project on demand for scale-sensitivity tests.
 *
 * <p>The fixture has one {@code com.example.scale.hub.Hub} class declaring
 * {@code public int counter}, plus {@code PACKAGES * CLASSES_PER_PACKAGE} leaf
 * classes that each reference {@code Hub.counter}. The leaf references give
 * {@code rename_symbol}, {@code find_references}, and {@code find_unused_code}
 * a non-trivial cross-class graph to operate on under JDT index pressure that
 * smaller fixtures cannot reproduce.
 *
 * <p>The fixture is built into a temporary directory the first time
 * {@link #getOrCreate()} is called in the JVM, then cached. The directory
 * lives for the JVM's lifetime — callers that need a fresh copy should
 * {@link #copyFixtureTo(Path)} into their own temp space.
 */
public final class ScaleFixtureGenerator {

    public static final int PACKAGES = 100;
    public static final int CLASSES_PER_PACKAGE = 10;
    public static final int LEAF_CLASS_COUNT = PACKAGES * CLASSES_PER_PACKAGE;

    private static volatile Path cached;

    private ScaleFixtureGenerator() {}

    /**
     * Return the cached scale-fixture root, generating it the first time
     * this method is called in the current JVM.
     */
    public static synchronized Path getOrCreate() throws IOException {
        if (cached != null && Files.exists(cached)) {
            return cached;
        }
        Path root = Files.createTempDirectory("javalens-scale-");
        writePom(root);
        writeHubClass(root);
        writeLeafClasses(root);
        cached = root;
        return root;
    }

    /**
     * Deep-copy the cached fixture into {@code destination}. Useful for tests
     * that modify source files and need an isolated copy.
     */
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

                import com.example.scale.hub.Hub;

                public class Class%d {
                    public void touch(Hub h) {
                        h.counter = h.counter + 1;
                    }
                }
                """.formatted(pkg, classIdx);
    }
}
