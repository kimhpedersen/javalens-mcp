package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.IType;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug B — annotation-processor / build-system generated source directories were never
 * added as source folders, so references to generated symbols (Lombok getters, MapStruct
 * mappers, JPA metamodels, ...) showed as unresolved during analysis.
 *
 * <p>The fix probes Maven {@code target/generated-sources/*} and Gradle
 * {@code build/generated/sources/*} as source folders during project import.
 *
 * <p>This test pre-creates a {@code Generated.java} under
 * {@code target/generated-sources/annotations/} (target/ is gitignored, so the fixture
 * doesn't ship it) and asserts both the source-folder discovery and the resulting type
 * resolution.
 */
class GeneratedSourcesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("Maven: target/generated-sources/* directories are added as source folders")
    void mavenGeneratedSourcesAddedAsSourceFolder() throws Exception {
        Path projectRoot = helper.copyFixture("with-generated-sources-maven");

        // Stand in for what an annotation processor would write at build time.
        Path generatedDir = projectRoot.resolve("target/generated-sources/annotations/com/example");
        Files.createDirectories(generatedDir);
        Files.writeString(generatedDir.resolve("Generated.java"), """
            package com.example;
            public final class Generated {
                public static final String HELLO = "hello";
                private Generated() {}
            }
            """);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("target/generated-sources/annotations")),
            "Expected target/generated-sources/annotations among source folders. " +
            "Got: " + snapshot.sourceFolders());

        IType generated = service.findType("com.example.Generated");
        assertNotNull(generated, "Expected to resolve com.example.Generated through JDT");
    }

    @Test
    @DisplayName("Maven: target/generated-test-sources/* directories are added as source folders")
    void mavenGeneratedTestSourcesAddedAsSourceFolder() throws Exception {
        // addGeneratedSourcePaths probes BOTH target/generated-sources/* AND
        // target/generated-test-sources/* — these are distinct code paths for two distinct
        // Maven outputs. Test-source processors (e.g. JPA Metamodel for test entities) are
        // a real-world case; without this branch those generated test files are unresolved
        // during analysis of test code.
        Path projectRoot = helper.copyFixture("with-generated-sources-maven");

        Path generatedTestDir = projectRoot.resolve("target/generated-test-sources/test-annotations/com/example");
        Files.createDirectories(generatedTestDir);
        Files.writeString(generatedTestDir.resolve("GeneratedTest.java"), """
            package com.example;
            public final class GeneratedTest {
                public static final String FIXTURE = "fixture";
                private GeneratedTest() {}
            }
            """);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("target/generated-test-sources/test-annotations")),
            "Expected target/generated-test-sources/test-annotations among source folders. "
                + "Got: " + snapshot.sourceFolders());

        IType generated = service.findType("com.example.GeneratedTest");
        assertNotNull(generated, "Expected to resolve com.example.GeneratedTest through JDT");
    }

    @Test
    @DisplayName("Maven: multiple processor subdirectories under target/generated-sources/ all become source folders")
    void mavenMultipleProcessorSubdirsAllAddedAsSourceFolders() throws Exception {
        // addImmediateSubdirectories iterates Files.list — every immediate child directory
        // becomes a source folder. Real projects often have multiple processors writing to
        // separate subdirs (annotations from Lombok/MapStruct, jpamodelgen from Hibernate,
        // ...). A single-dir test wouldn't catch a regression that picked only the first.
        Path projectRoot = helper.copyFixture("with-generated-sources-maven");

        Path annotations = projectRoot.resolve("target/generated-sources/annotations/com/example");
        Path jpa = projectRoot.resolve("target/generated-sources/jpamodelgen/com/example");
        Files.createDirectories(annotations);
        Files.createDirectories(jpa);
        Files.writeString(annotations.resolve("FromAnnotations.java"), """
            package com.example;
            public final class FromAnnotations { private FromAnnotations() {} }
            """);
        Files.writeString(jpa.resolve("FromJpa.java"), """
            package com.example;
            public final class FromJpa { private FromJpa() {} }
            """);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("target/generated-sources/annotations")),
            "Expected target/generated-sources/annotations among source folders; got: "
                + snapshot.sourceFolders());
        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("target/generated-sources/jpamodelgen")),
            "Expected target/generated-sources/jpamodelgen among source folders; got: "
                + snapshot.sourceFolders());

        assertNotNull(service.findType("com.example.FromAnnotations"),
            "Expected FromAnnotations to resolve from the annotations/ subdir");
        assertNotNull(service.findType("com.example.FromJpa"),
            "Expected FromJpa to resolve from the jpamodelgen/ subdir");
    }

    @Test
    @DisplayName("Gradle: build/generated/sources/<task>/main/java is added as source folder")
    void gradleGeneratedSourcesAddedAsSourceFolder() throws Exception {
        Path projectRoot = helper.copyFixture("with-generated-sources-gradle");

        // Stand in for what `annotationProcessor` would write at build time.
        Path generatedDir = projectRoot.resolve("build/generated/sources/annotationProcessor/main/java/com/example");
        Files.createDirectories(generatedDir);
        Files.writeString(generatedDir.resolve("Generated.java"), """
            package com.example;
            public final class Generated {
                public static final String HELLO = "hello";
                private Generated() {}
            }
            """);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("build/generated/sources/annotationProcessor/main/java")),
            "Expected build/generated/sources/annotationProcessor/main/java among source folders. " +
            "Got: " + snapshot.sourceFolders());

        IType generated = service.findType("com.example.Generated");
        assertNotNull(generated, "Expected to resolve com.example.Generated through JDT");
    }

    @Test
    @DisplayName("Gradle: build/generated/sources/<task>/test/java is added as source folder")
    void gradleGeneratedTestSourcesAddedAsSourceFolder() throws Exception {
        // addGeneratedSourcePaths iterates the <task> directories and probes BOTH
        // main/java and test/java. The test branch is its own code path; covering only
        // main/java leaves a regression that drops the test branch undetected.
        Path projectRoot = helper.copyFixture("with-generated-sources-gradle");

        Path testDir = projectRoot.resolve(
            "build/generated/sources/annotationProcessor/test/java/com/example");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("GeneratedTest.java"), """
            package com.example;
            public final class GeneratedTest { private GeneratedTest() {} }
            """);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("build/generated/sources/annotationProcessor/test/java")),
            "Expected build/generated/sources/annotationProcessor/test/java among source folders; "
                + "got: " + snapshot.sourceFolders());

        assertNotNull(service.findType("com.example.GeneratedTest"),
            "Expected GeneratedTest to resolve from the annotationProcessor/test/java dir");
    }

    @Test
    @DisplayName("Gradle: multiple <task> subdirectories all become source folders")
    void gradleMultipleTaskSubdirsAllAddedAsSourceFolders() throws Exception {
        // Real projects can declare multiple source-generating tasks: annotationProcessor,
        // kapt, ksp, openapi-generator, etc. The source code's Files.list iteration must
        // visit every task subdir. Single-task coverage misses a regression that picked
        // only the first.
        Path projectRoot = helper.copyFixture("with-generated-sources-gradle");

        Path aptMain = projectRoot.resolve(
            "build/generated/sources/annotationProcessor/main/java/com/example");
        Path kaptMain = projectRoot.resolve(
            "build/generated/sources/kapt/main/java/com/example");
        Files.createDirectories(aptMain);
        Files.createDirectories(kaptMain);
        Files.writeString(aptMain.resolve("FromApt.java"), """
            package com.example;
            public final class FromApt { private FromApt() {} }
            """);
        Files.writeString(kaptMain.resolve("FromKapt.java"), """
            package com.example;
            public final class FromKapt { private FromKapt() {} }
            """);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("build/generated/sources/annotationProcessor/main/java")),
            "Expected annotationProcessor/main/java among source folders; got: "
                + snapshot.sourceFolders());
        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("build/generated/sources/kapt/main/java")),
            "Expected kapt/main/java among source folders; got: " + snapshot.sourceFolders());

        assertNotNull(service.findType("com.example.FromApt"),
            "Expected FromApt to resolve from annotationProcessor/main/java");
        assertNotNull(service.findType("com.example.FromKapt"),
            "Expected FromKapt to resolve from kapt/main/java");
    }
}
