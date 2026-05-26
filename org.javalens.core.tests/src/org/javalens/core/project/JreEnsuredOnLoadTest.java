package org.javalens.core.project;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the post-condition of {@code ProjectImporter.configureJavaProject} that
 * issue #18's fix introduced: after {@code loadProject}, the project must have
 * a backing {@link IVMInstall} and the resolved classpath must include the JDK
 * system modules.
 *
 * <p>In a Tycho-driven test runtime, JDT's auto-detection covers the same case
 * — so these tests pass even before the fix lands. Their purpose is the
 * regression contract: if a future change ever removes the explicit
 * {@link JreInstallEnsurer} call and JDT's fallback also fails (which is
 * exactly issue #18's environment-specific scenario), these tests catch it
 * because they require an IVMInstall to be associated with the project, not
 * just for it to appear by accident.
 */
class JreEnsuredOnLoadTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("After loadProject, an IVMInstall is associated with the project")
    void loadProject_associatesIvmInstallWithProject() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        IJavaProject project = service.getJavaProject();

        IVMInstall vm = JavaRuntime.getVMInstall(project);
        assertNotNull(vm,
            "loadProject must produce a project whose JRE container resolves to a real "
                + "IVMInstall — without it, java.lang.Object and the rest of the bootstrap "
                + "module are unreachable and every source file produces a BUILDPATH cascade "
                + "(issue #18).");
    }

    @Test
    @DisplayName("Resolved classpath after loadProject contains a JDK system-module entry")
    void loadProject_resolvedClasspathIncludesBootstrap() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        IJavaProject project = service.getJavaProject();
        IClasspathEntry[] resolved = project.getResolvedClasspath(true);

        boolean hasBootstrap = Arrays.stream(resolved)
            .filter(e -> e.getEntryKind() == IClasspathEntry.CPE_LIBRARY)
            .map(e -> e.getPath().toString())
            .anyMatch(p -> p.contains("jrt-fs.jar")
                || p.contains("java.base")
                || p.endsWith("rt.jar")
                || p.startsWith("jrt:"));

        assertTrue(hasBootstrap,
            "Resolved classpath must contain a JDK bootstrap entry (jrt-fs.jar / java.base / "
                + "rt.jar / jrt:); otherwise ECJ cannot find java.lang.Object. Resolved entries: "
                + Arrays.stream(resolved).map(e -> e.getPath().toString()).toList());
    }

    @Test
    @DisplayName("loadProject does not emit JRE_REGISTRATION_FAILED warning in a healthy environment")
    void loadProject_doesNotWarnAboutJreInHealthyEnv() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        List<LoadWarning> warnings = service.getWarnings();

        boolean hasJreWarning = warnings.stream()
            .anyMatch(w -> LoadWarning.JRE_REGISTRATION_FAILED.equals(w.code()));
        assertFalse(hasJreWarning,
            "The test environment has a working java.home; the JRE registration warning must "
                + "not fire. If it does, java.home is unset/invalid for the test JVM. "
                + "Warnings: " + warnings);
    }
}
