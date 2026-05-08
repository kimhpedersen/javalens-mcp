package org.javalens.core.fixtures;

import org.junit.jupiter.api.Assumptions;

/**
 * Test-environment gate for external tool requirements (mvn / gradle / bazel binaries,
 * symlink permissions, ...).
 *
 * <p>Local dev: tests {@code assumeAvailable} into a skip when the tool isn't present, so
 * a developer on a clean machine doesn't see false failures.
 *
 * <p>CI: setting the {@code JAVALENS_TESTS_REQUIRE_TOOLS=true} environment variable flips
 * the same gate into a hard assertion. CI installs every tool, so any skip would indicate
 * a provisioning gap and would silently weaken the suite. With the env var set, the test
 * fails loudly instead, exposing the gap.
 */
public final class TestEnvironment {

    private static final String REQUIRE_TOOLS_ENV = "JAVALENS_TESTS_REQUIRE_TOOLS";

    private TestEnvironment() {}

    /**
     * Skip the current test (locally) or fail it (in CI) when {@code value} is null/false.
     * The {@code description} appears in the skip reason or assertion message.
     */
    public static void requireOrSkip(boolean available, String description) {
        if (available) return;
        if (toolsRequired()) {
            throw new AssertionError(
                description + " is required but unavailable. " +
                "JAVALENS_TESTS_REQUIRE_TOOLS=true means every gated tool must be present " +
                "in the test environment. Install the missing tool or remove the env var.");
        }
        Assumptions.abort("Skipped: " + description + " is not available in this environment");
    }

    /** Convenience for object-presence checks. */
    public static void requireOrSkip(Object value, String description) {
        requireOrSkip(value != null, description);
    }

    public static boolean toolsRequired() {
        return Boolean.parseBoolean(System.getenv(REQUIRE_TOOLS_ENV));
    }
}
