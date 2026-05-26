package org.javalens.core.project;

import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstallType;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the contract of {@link JreInstallEnsurer} — the helper that guarantees
 * an {@link IVMInstall} backing the project's JRE container exists before
 * {@code loadProject} sets up the project's classpath.
 *
 * <p>JDT's default behavior is to auto-detect a JRE via
 * {@code JavaRuntime.detectEclipseRuntime} on the first call to
 * {@link JavaRuntime#getDefaultVMInstall()}. That fallback succeeds in most
 * environments but not all (issue #18 surfaces a Mac aarch64 / Corretto / npm
 * launch where it doesn't fire). The helper makes the project's JRE
 * presence independent of whether the fallback works.
 *
 * <p>Stable-ID idempotency: the helper derives a deterministic ID from
 * {@code java.home} so repeated calls produce a single registration per JDK
 * location, regardless of how many JavaLens sessions run.
 */
class JreInstallEnsurerTest {

    @Test
    @DisplayName("returns an IVMInstall whose install location equals java.home of the running JVM")
    void ensureRunningJvmRegistered_returnsInstallAtJavaHome() throws Exception {
        IVMInstall install = JreInstallEnsurer.ensureRunningJvmRegistered();
        assertNotNull(install,
            "Helper must return an IVMInstall in any environment where the running JVM "
                + "has a valid java.home (always true under normal launch)");

        File installLocation = install.getInstallLocation();
        assertNotNull(installLocation, "Returned IVMInstall must carry an install location");

        File expectedJavaHome = new File(System.getProperty("java.home")).getCanonicalFile();
        assertEquals(expectedJavaHome, installLocation.getCanonicalFile(),
            "Install location must equal java.home of the running JVM; got: " + installLocation);
    }

    @Test
    @DisplayName("returns the same IVMInstall on repeated calls (idempotent, no duplicate registrations)")
    void ensureRunningJvmRegistered_isIdempotent() {
        IVMInstall first = JreInstallEnsurer.ensureRunningJvmRegistered();
        IVMInstall second = JreInstallEnsurer.ensureRunningJvmRegistered();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.getId(), second.getId(),
            "Repeated calls must produce the same IVMInstall id — stable ID derived "
                + "from java.home gives one entry per JDK location, not per session");
    }

    @Test
    @DisplayName("sets the returned IVMInstall as the JDT default")
    void ensureRunningJvmRegistered_setsAsDefault() throws Exception {
        IVMInstall install = JreInstallEnsurer.ensureRunningJvmRegistered();
        IVMInstall defaultVm = JavaRuntime.getDefaultVMInstall();
        assertNotNull(defaultVm,
            "After ensureRunningJvmRegistered, JavaRuntime.getDefaultVMInstall must be non-null");
        assertEquals(install.getId(), defaultVm.getId(),
            "Returned install must be the JDT default; otherwise the JRE container "
                + "(bare JRE_CONTAINER path) resolves to a different VM");
    }

    @Test
    @DisplayName("returned IVMInstall is registered under StandardVMType")
    void ensureRunningJvmRegistered_typeIsStandardVm() {
        IVMInstall install = JreInstallEnsurer.ensureRunningJvmRegistered();
        IVMInstallType type = install.getVMInstallType();
        assertNotNull(type);
        assertTrue(type.getId().contains("StandardVMType"),
            "IVMInstall must be registered under StandardVMType so JDT's JRE container "
                + "initializer recognizes it; got typeId: " + type.getId());
    }

    @Test
    @DisplayName("registered IVMInstall is discoverable via JavaRuntime.getVMInstallType().getVMInstalls()")
    void ensureRunningJvmRegistered_isDiscoverableInRegistry() {
        IVMInstall install = JreInstallEnsurer.ensureRunningJvmRegistered();
        IVMInstallType type = install.getVMInstallType();

        IVMInstall[] all = type.getVMInstalls();
        boolean found = false;
        for (IVMInstall vm : all) {
            if (vm.getId().equals(install.getId())) {
                found = true;
                assertSame(install, vm,
                    "The IVMInstall returned by the helper must be the same instance "
                        + "the type's registry holds — otherwise lookups via the type "
                        + "won't match the helper's result");
                break;
            }
        }
        assertTrue(found,
            "Registered IVMInstall must be in the type's getVMInstalls() list; got "
                + all.length + " installs, none matching id " + install.getId());
    }

    @Test
    @DisplayName("ID is stable across calls — derived from java.home, not generated per session")
    void ensureRunningJvmRegistered_idIsDeterministic() {
        String firstId = JreInstallEnsurer.ensureRunningJvmRegistered().getId();
        String secondId = JreInstallEnsurer.ensureRunningJvmRegistered().getId();
        assertEquals(firstId, secondId);
        assertFalse(firstId.isBlank(),
            "ID must be a non-blank stable identifier; got: " + firstId);
    }
}
