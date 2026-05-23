package org.javalens.core.buildsystem;

import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins JavaLens's load behavior for a Gradle composite build (root project
 * declares {@code includeBuild 'shared-lib'} in settings.gradle, with shared-lib
 * a sibling Gradle build under the root directory).
 *
 * <p>The composite-build fixture exists primarily as a regression guard: JavaLens
 * should not crash, hang, or produce a wholly empty project when loading a Gradle
 * root that includes a sibling build. Full cross-build symbol resolution depends on
 * Gradle running and emitting the dependency artifacts; the fixture only verifies
 * the root project loads and its own source file is reachable.
 */
class CompositeGradleTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("Composite Gradle root loads without crashing")
    void compositeGradle_loadsCleanly() throws Exception {
        JdtServiceImpl service = helper.loadProject("composite-gradle");

        assertNotNull(service.getJavaProject(), "JavaProject must exist after load");
        assertTrue(service.getJavaProject().exists(),
            "JavaProject must report exists() after composite-build load");

        Path settings = helper.getFixturePath("composite-gradle").resolve("settings.gradle");
        assertTrue(Files.exists(settings),
            "settings.gradle must be present in the fixture: " + settings);
        String settingsContent = Files.readString(settings);
        assertTrue(settingsContent.contains("includeBuild 'shared-lib'"),
            "settings.gradle must declare the includeBuild directive for the test " +
                "to actually exercise the composite-build code path; got: " + settingsContent);
    }

    @Test
    @DisplayName("Root build's App class resolves via findType")
    void compositeGradle_rootAppResolvesViaFindType() throws Exception {
        JdtServiceImpl service = helper.loadProject("composite-gradle");

        var app = service.findType("com.example.composite.App");
        assertNotNull(app,
            "findType must resolve the root build's App class even when settings.gradle " +
                "declares includeBuild");
        assertTrue(app.exists(), "App IType must report exists()");
    }

    @Test
    @DisplayName("Both build directories are present on disk — fixture sanity")
    void compositeGradle_bothBuildsPresent() throws Exception {
        Path root = helper.getFixturePath("composite-gradle");
        assertTrue(Files.exists(root.resolve("build.gradle")),
            "Root build.gradle must be present");
        assertTrue(Files.exists(root.resolve("shared-lib/build.gradle")),
            "Included build's build.gradle must be present");
        assertTrue(Files.exists(root.resolve("shared-lib/settings.gradle")),
            "Included build must have its own settings.gradle (composite-build requirement)");
        assertTrue(Files.exists(root.resolve("shared-lib/src/main/java/com/example/sharedlib/SharedUtil.java")),
            "Shared library source must be present in the included build");
    }

    @Test
    @DisplayName("Root build's source files are enumerated by getAllJavaFiles after load")
    void compositeGradle_enumeratesRootSources() throws Exception {
        JdtServiceImpl service = helper.loadProject("composite-gradle");
        long appCount = service.getAllJavaFiles().stream()
            .map(p -> p.toString().replace('\\', '/'))
            .filter(s -> s.endsWith("src/main/java/com/example/composite/App.java"))
            .count();
        assertEquals(1, appCount,
            "Root build's App.java must be enumerated exactly once by getAllJavaFiles");
    }

    @Test
    @DisplayName("Cross-build symbol resolution: SharedUtil from included build either resolves or is documented unavailable")
    void compositeGradle_crossBuildResolutionContract() throws Exception {
        // The composite-build contract for JavaLens (without Gradle execution): the
        // included build's classes MAY or MAY NOT resolve via findType — Gradle would
        // emit dependency artifacts that JDT could see, and we don't run Gradle in tests.
        // What we pin here is that the load doesn't crash AND the failure mode (if any)
        // is clean: findType returns null rather than throwing.
        JdtServiceImpl service = helper.loadProject("composite-gradle");
        var sharedUtil = service.findType("com.example.sharedlib.SharedUtil");
        // Either path is acceptable on the contract surface:
        // - null: JavaLens correctly reports the cross-build type is unresolvable without
        //   a Gradle build run. Consumers can call build first.
        // - non-null + exists: JavaLens's importer surfaces the included build's sources.
        if (sharedUtil != null) {
            assertTrue(sharedUtil.exists(),
                "If findType returns a non-null SharedUtil, it must report exists()=true");
            assertEquals("SharedUtil", sharedUtil.getElementName());
        }
        // The load itself must stay green regardless.
        assertTrue(service.getJavaProject().exists(),
            "JavaProject must remain valid after a findType attempt on a cross-build type");
    }
}
