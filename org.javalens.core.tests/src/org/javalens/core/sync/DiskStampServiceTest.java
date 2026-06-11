package org.javalens.core.sync;

import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.sync.DiskStampService.ChangeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the disk-truth change-detection contract: content-hash stamps over the
 * source roots plus explicit build files; verify() reports exactly what
 * changed on disk (edits by hash mismatch, adds/deletes by walk diff, build
 * files separately); detection never trusts metadata alone. Pure filesystem -
 * no JDT involvement at this layer.
 */
class DiskStampServiceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private Path projectCopy;
    private Path mainRoot;
    private Path testRoot;
    private Path pom;
    private DiskStampService service;

    @BeforeEach
    void setUp() throws Exception {
        projectCopy = helper.copyFixture("simple-maven");
        mainRoot = projectCopy.resolve("src/main/java");
        testRoot = projectCopy.resolve("src/test/java");
        pom = projectCopy.resolve("pom.xml");
        service = new DiskStampService(List.of(mainRoot, testRoot), List.of(pom));
        service.stampAll();
    }

    private Path calculator() {
        return mainRoot.resolve("com/example/Calculator.java");
    }

    // ========== No change ==========

    @Test
    @DisplayName("an untouched tree verifies as empty")
    void noChange_emptyChangeSet() throws IOException {
        ChangeSet changes = service.verify();
        assertTrue(changes.isEmpty(), () -> "expected no changes; got: " + changes);
        assertEquals(List.of(), changes.edited());
        assertEquals(List.of(), changes.added());
        assertEquals(List.of(), changes.deleted());
        assertEquals(List.of(), changes.buildFilesChanged());
    }

    // ========== Edits ==========

    @Test
    @DisplayName("a content edit is detected as exactly that file")
    void edit_detected() throws IOException {
        String source = Files.readString(calculator());
        Files.writeString(calculator(), source + "// edited\n");

        ChangeSet changes = service.verify();
        assertEquals(List.of(calculator().toAbsolutePath().normalize()), changes.edited());
        assertEquals(List.of(), changes.added());
        assertEquals(List.of(), changes.deleted());
    }

    @Test
    @DisplayName("a same-length edit is detected (content, not size or timestamps)")
    void sameLengthEdit_detected() throws IOException {
        String source = Files.readString(calculator());
        // Same byte count, different content - invisible to size checks.
        Files.writeString(calculator(), source.replace("int add(", "int sum("));

        ChangeSet changes = service.verify();
        assertEquals(List.of(calculator().toAbsolutePath().normalize()), changes.edited());
    }

    // ========== Adds / deletes / renames ==========

    @Test
    @DisplayName("a new file is detected, including in a brand-new package")
    void add_detectedIncludingNewPackage() throws IOException {
        Path newPackageFile = mainRoot.resolve("com/example/fresh/Brand.java");
        Files.createDirectories(newPackageFile.getParent());
        Files.writeString(newPackageFile, "package com.example.fresh;\n\npublic class Brand {\n}\n");

        ChangeSet changes = service.verify();
        assertEquals(List.of(newPackageFile.toAbsolutePath().normalize()), changes.added());
        assertEquals(List.of(), changes.edited());
        assertEquals(List.of(), changes.deleted());
    }

    @Test
    @DisplayName("a deleted file is detected as exactly that file")
    void delete_detected() throws IOException {
        Files.delete(calculator());

        ChangeSet changes = service.verify();
        assertEquals(List.of(calculator().toAbsolutePath().normalize()), changes.deleted());
        assertEquals(List.of(), changes.edited());
        assertEquals(List.of(), changes.added());
    }

    @Test
    @DisplayName("a rename is detected as one delete plus one add")
    void rename_detectedAsDeletePlusAdd() throws IOException {
        Path renamed = calculator().resolveSibling("Calculon.java");
        Files.move(calculator(), renamed);

        ChangeSet changes = service.verify();
        assertEquals(List.of(calculator().toAbsolutePath().normalize()), changes.deleted());
        assertEquals(List.of(renamed.toAbsolutePath().normalize()), changes.added());
    }

    @Test
    @DisplayName("multiple simultaneous changes are all reported in one pass")
    void multiFileBatch_allReported() throws IOException {
        String source = Files.readString(calculator());
        Files.writeString(calculator(), source + "// edited\n");
        Path added = mainRoot.resolve("com/example/Extra.java");
        Files.writeString(added, "package com.example;\n\npublic class Extra {\n}\n");
        Path animal = mainRoot.resolve("com/example/Animal.java");
        Files.delete(animal);

        ChangeSet changes = service.verify();
        assertEquals(List.of(calculator().toAbsolutePath().normalize()), changes.edited());
        assertEquals(List.of(added.toAbsolutePath().normalize()), changes.added());
        assertEquals(List.of(animal.toAbsolutePath().normalize()), changes.deleted());
    }

    // ========== Build files ==========

    @Test
    @DisplayName("a build-file edit is reported separately, not as a source edit")
    void buildFileEdit_reportedSeparately() throws IOException {
        String content = Files.readString(pom);
        Files.writeString(pom, content.replace("</project>", "    <!-- touched -->\n</project>"));

        ChangeSet changes = service.verify();
        assertEquals(List.of(pom.toAbsolutePath().normalize()), changes.buildFilesChanged());
        assertEquals(List.of(), changes.edited());
        assertFalse(changes.isEmpty());
    }

    @Test
    @DisplayName("a deleted build file is reported as a build-file change")
    void buildFileDeleted_reported() throws IOException {
        Files.delete(pom);

        ChangeSet changes = service.verify();
        assertEquals(List.of(pom.toAbsolutePath().normalize()), changes.buildFilesChanged());
    }

    // ========== Scope ==========

    @Test
    @DisplayName("non-Java, non-build files are outside the contract")
    void unrelatedFile_ignored() throws IOException {
        Files.writeString(projectCopy.resolve("notes.txt"), "scratch");
        Files.writeString(mainRoot.resolve("com/example/data.json"), "{}");

        ChangeSet changes = service.verify();
        assertTrue(changes.isEmpty(), () -> "non-source files must not register; got: " + changes);
    }

    @Test
    @DisplayName("build output directories below a root are not walked")
    void outputDirectories_skipped() throws IOException {
        Path target = mainRoot.resolve("target/Generated.java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "public class Generated {}");

        ChangeSet changes = service.verify();
        assertTrue(changes.isEmpty(), () -> "target/ content must be skipped; got: " + changes);
    }

    // ========== Restamp (chain of custody) ==========

    @Test
    @DisplayName("restamping an edited file clears it from subsequent verifies")
    void restamp_clearsEditedFile() throws IOException {
        String source = Files.readString(calculator());
        Files.writeString(calculator(), source + "// edited\n");
        assertEquals(1, service.verify().edited().size());

        service.restamp(List.of(calculator()));
        assertTrue(service.verify().isEmpty(), "restamped file must verify clean");
    }

    @Test
    @DisplayName("restamp accepts denormalized paths (separator/normalization safety)")
    void restamp_normalizesPaths() throws IOException {
        String source = Files.readString(calculator());
        Files.writeString(calculator(), source + "// edited\n");

        String denormalized = calculator().toAbsolutePath().toString().replace('\\', '/')
            .replace("com/example", "com/./example");
        service.restamp(List.of(Path.of(denormalized)));

        assertTrue(service.verify().isEmpty(),
            "a denormalized spelling of the same file must hit the same stamp");
    }

    @Test
    @DisplayName("restamping an added file registers it; a deleted file is forgotten")
    void restamp_handlesAddAndDelete() throws IOException {
        Path added = mainRoot.resolve("com/example/Extra.java");
        Files.writeString(added, "package com.example;\n\npublic class Extra {\n}\n");
        Files.delete(calculator());

        service.restamp(List.of(added, calculator()));
        assertTrue(service.verify().isEmpty(),
            "after restamping the add and the delete, the tree must verify clean");
        assertEquals(service.stampedFileCount(), countJavaFiles());
    }

    private int countJavaFiles() throws IOException {
        try (var walk = Files.walk(mainRoot); var walk2 = Files.walk(testRoot)) {
            long main = walk.filter(p -> p.toString().endsWith(".java")).count();
            long test = walk2.filter(p -> p.toString().endsWith(".java")).count();
            return (int) (main + test) + 1; // + the stamped pom.xml
        }
    }

    // ========== Failure is loud ==========

    @Test
    @DisplayName("a vanished source root fails loud, not as a mass delete")
    void missingRoot_throws() throws IOException {
        Files.walk(testRoot)
            .sorted(Comparator.reverseOrder())
            .forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

        assertThrows(IOException.class, () -> service.verify(),
            "a missing source root is a structural failure, not a change set");
    }

    // ========== Timing (logged, not asserted) ==========

    @Test
    @DisplayName("measurement: stampAll and verify timings on simple-maven")
    void timing_logged() throws IOException {
        long t0 = System.nanoTime();
        service.stampAll();
        long stampMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        service.verify();
        long verifyMs = (System.nanoTime() - t1) / 1_000_000;

        System.out.printf("[disk-sync timing] simple-maven: files=%d stampAll=%dms verify(no-change)=%dms%n",
            service.stampedFileCount(), stampMs, verifyMs);
        assertTrue(service.stampedFileCount() > 0);
    }
}
