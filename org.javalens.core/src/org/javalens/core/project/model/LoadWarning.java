package org.javalens.core.project.model;

/**
 * Structured warning surfaced during project load.
 *
 * <p>Bug X fix: when external build-tool subprocesses (mvn/gradle/bazel) fail or produce
 * incomplete output, JavaLens previously returned an empty classpath silently. Loaders now
 * accumulate warnings into the {@code JdtServiceImpl} and the {@code load_project} response
 * surfaces them so MCP clients have a signal that analysis quality may be degraded.
 *
 * <p>Stable codes (use these in tests and downstream consumers):
 * <ul>
 *   <li>{@code MAVEN_SUBPROCESS_FAILED} — {@code mvn} could not start, exited non-zero, or
 *       its declared output file is missing.</li>
 *   <li>{@code MAVEN_SUBPROCESS_TIMEOUT} — {@code mvn} did not finish within the configured
 *       wait window.</li>
 *   <li>{@code GRADLE_SUBPROCESS_FAILED} — same shape as Maven, for Gradle.</li>
 *   <li>{@code BAZEL_NOT_BUILT} — {@code bazel-bin/}/{@code bazel-out/} not populated; user
 *       has not run {@code bazel build} recently.</li>
 *   <li>{@code COMPLIANCE_LEVEL_UNKNOWN} — could not determine the project's compiler
 *       source/target level; using JVM defaults.</li>
 *   <li>{@code APT_PROCESSOR_JARS_MISSING} — annotation processors were declared but
 *       every candidate jar was missing from disk at wiring time; APT is enabled with
 *       an empty factory path so generated members will not resolve.</li>
 * </ul>
 *
 * @param code stable identifier (see list above)
 * @param message human-readable description of what happened
 * @param remediation actionable hint for the user (e.g., "Install Maven and ensure mvn is on PATH")
 * @param module optional module identifier; null for project-wide warnings
 */
public record LoadWarning(String code, String message, String remediation, String module) {

    public static final String MAVEN_SUBPROCESS_FAILED = "MAVEN_SUBPROCESS_FAILED";
    public static final String MAVEN_SUBPROCESS_TIMEOUT = "MAVEN_SUBPROCESS_TIMEOUT";
    public static final String GRADLE_SUBPROCESS_FAILED = "GRADLE_SUBPROCESS_FAILED";
    public static final String BAZEL_NOT_BUILT = "BAZEL_NOT_BUILT";
    public static final String COMPLIANCE_LEVEL_UNKNOWN = "COMPLIANCE_LEVEL_UNKNOWN";
    /**
     * Annotation processors were declared by the project's build files but every candidate
     * jar failed the {@code Files.isRegularFile} probe at APT wiring time. APT is left
     * enabled with an empty factory path — analysis of processor-generated members will
     * silently return wrong results. Typical cause: stale paths cached from a previous
     * build, or {@code ~/.m2}/Gradle cache eviction between dependency resolution and
     * project load.
     */
    public static final String APT_PROCESSOR_JARS_MISSING = "APT_PROCESSOR_JARS_MISSING";

    public LoadWarning(String code, String message, String remediation) {
        this(code, message, remediation, null);
    }
}
