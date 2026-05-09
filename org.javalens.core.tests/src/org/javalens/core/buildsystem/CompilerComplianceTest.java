package org.javalens.core.buildsystem;

import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.LoadedFixture;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.LoadWarning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug G — Maven compiler source level not applied to {@code IJavaProject}.
 *
 * <p>Without this fix, {@code JavaCore.COMPILER_SOURCE} / {@code COMPILER_COMPLIANCE} fall
 * back to JDT defaults regardless of what the project declares. Source code that uses
 * Java 21 features (for example record patterns from JEP 440) gets parsed against an older
 * grammar and reports spurious syntax errors.
 *
 * <p>The fix reads {@code maven.compiler.release} > {@code maven.compiler.source} >
 * {@code maven.compiler.target} from {@code pom.xml} and calls {@code setOption} on the
 * configured {@link org.eclipse.jdt.core.IJavaProject}.
 */
class CompilerComplianceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("Maven source level from pom is applied to IJavaProject options")
    void mavenSourceLevelAppliedFromPom() throws Exception {
        // The fixture deliberately declares 17 (different from the workspace/runtime default
        // of 21) so this assertion can only pass when the per-project option was actually
        // read from pom and set on the IJavaProject.
        LoadedFixture fixture = helper.loadFixture("compliance-level-mismatch");

        assertEquals("17", fixture.classpath().compilerSource(),
            "Expected COMPILER_SOURCE to come from pom <maven.compiler.source>");
        assertEquals("17", fixture.classpath().compilerCompliance(),
            "Expected COMPILER_COMPLIANCE to come from pom <maven.compiler.source>");
    }

    @Test
    @DisplayName("project with declared compiler level produces no COMPLIANCE_LEVEL_UNKNOWN warning")
    void declaredLevelProducesNoComplianceWarning() throws Exception {
        LoadedFixture fixture = helper.loadFixture("compliance-level-mismatch");

        List<LoadWarning> warnings = fixture.service().getWarnings();
        boolean hasComplianceWarning = warnings.stream()
            .anyMatch(w -> LoadWarning.COMPLIANCE_LEVEL_UNKNOWN.equals(w.code()));
        assertTrue(!hasComplianceWarning,
            "Did not expect COMPLIANCE_LEVEL_UNKNOWN when pom declares maven.compiler.source. " +
            "Warnings: " + warnings);
    }

    @Test
    @DisplayName("plain Java project falls back to runtime JVM major version")
    void plainJavaFallsBackToRuntimeVersion() throws Exception {
        // No pom.xml / build.gradle / BUILD.bazel — applyCompilerOptions's fallback uses
        // Runtime.version().feature() rather than silently inheriting a JDT default.
        LoadedFixture fixture = helper.loadFixture("plain-java");

        String expected = String.valueOf(Runtime.version().feature());
        assertEquals(expected, fixture.classpath().compilerSource(),
            "Expected COMPILER_SOURCE to default to the runtime JVM major version when " +
            "no build file declares a compliance level");
        assertEquals(expected, fixture.classpath().compilerCompliance(),
            "Expected COMPILER_COMPLIANCE to default to the runtime JVM major version");

        // Plain Java has no place to declare a level, so the fallback is expected — no
        // warning. (For Maven/Gradle/Bazel projects without a declared level,
        // COMPLIANCE_LEVEL_UNKNOWN fires; that path is covered by the
        // unknownLevelOnDetectedBuildSystemEmitsWarning case below.)
        boolean hasComplianceWarning = fixture.service().getWarnings().stream()
            .anyMatch(w -> LoadWarning.COMPLIANCE_LEVEL_UNKNOWN.equals(w.code()));
        assertTrue(!hasComplianceWarning,
            "COMPLIANCE_LEVEL_UNKNOWN should not fire on plain-Java projects. " +
            "Warnings: " + fixture.service().getWarnings());
    }

    @Test
    @DisplayName("Maven plugin <configuration><release> is read in addition to pom <properties>")
    void mavenComplianceFromPluginConfigurationRelease() throws Exception {
        LoadedFixture fixture = helper.loadFixture("compliance-from-plugin-config");

        assertEquals("17", fixture.classpath().compilerSource(),
            "Expected COMPILER_SOURCE to come from <plugin>maven-compiler-plugin</plugin>" +
            "<configuration><release>17</release></configuration>");
        assertEquals("17", fixture.classpath().compilerCompliance(),
            "Expected COMPILER_COMPLIANCE to come from the plugin <configuration> block");
    }

    @Test
    @DisplayName("COMPLIANCE_LEVEL_UNKNOWN fires when a build system declares no compiler level")
    void unknownLevelOnDetectedBuildSystemEmitsWarning() throws Exception {
        // Maven project with no maven.compiler.* property AND no maven-compiler-plugin
        // <configuration>. Detection identifies it as Maven, the level extractor returns
        // null, and we fall back to Runtime.version().feature() — but emit
        // COMPLIANCE_LEVEL_UNKNOWN so the agent knows analysis used the runtime default.
        LoadedFixture fixture = helper.loadFixture("maven-no-compliance-declared");

        boolean hasComplianceWarning = fixture.service().getWarnings().stream()
            .anyMatch(w -> LoadWarning.COMPLIANCE_LEVEL_UNKNOWN.equals(w.code()));
        assertTrue(hasComplianceWarning,
            "Expected COMPLIANCE_LEVEL_UNKNOWN when a Maven project declares no compiler " +
            "level. Warnings: " + fixture.service().getWarnings());

        // The fallback level itself should equal the runtime feature version so analysis
        // can still parse modern syntax.
        String expected = String.valueOf(Runtime.version().feature());
        assertEquals(expected, fixture.classpath().compilerSource(),
            "Expected runtime fallback when no compiler level is declared");
    }

    @Test
    @DisplayName("Maven plugin <configuration> works with artifactId-before-groupId ordering")
    void mavenComplianceFromPluginWithReversedIdentityOrder() throws Exception {
        // Maven's POM schema doesn't require groupId-before-artifactId. The regex must
        // accept either order. Mutate the copied fixture so artifactId comes first, then
        // load and assert compliance still resolves.
        Path projectRoot = helper.copyFixture("compliance-from-plugin-config");
        Path pom = projectRoot.resolve("pom.xml");
        String original = Files.readString(pom);
        String reversed = original.replace(
            "<groupId>org.apache.maven.plugins</groupId>\n" +
                "                <artifactId>maven-compiler-plugin</artifactId>",
            "<artifactId>maven-compiler-plugin</artifactId>\n" +
                "                <groupId>org.apache.maven.plugins</groupId>");
        Files.writeString(pom, reversed);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        assertEquals("17", snapshot.compilerSource(),
            "Expected COMPILER_SOURCE to resolve regardless of groupId/artifactId ordering");
        assertEquals("17", snapshot.compilerCompliance(),
            "Expected COMPILER_COMPLIANCE to resolve regardless of groupId/artifactId ordering");
    }
}
