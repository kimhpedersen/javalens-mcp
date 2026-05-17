package org.javalens.core.project;

/**
 * The build system that owns a Java project's classpath / module / compiler config.
 *
 * <p>Detected by {@link ProjectImporter#detectBuildSystem(java.nio.file.Path)} from
 * filesystem markers ({@code pom.xml}, {@code build.gradle}, {@code MODULE.bazel}, …).
 * Each value (except {@link #UNKNOWN}) has a corresponding {@link BuildSystemImporter}
 * registered with the orchestrator that knows how to assemble its classpath, detect
 * its compiler level, and discover its annotation processors.
 *
 * <p>{@link #UNKNOWN} is the "plain Java" case: no recognized build file, no project
 * dependencies, just a source tree. The orchestrator still loads it (with a JRE-only
 * classpath and a JVM-default compiler level), but the source-level fallback emits no
 * warning because there's no build file to declare a level in.
 */
public enum BuildSystem {
    MAVEN,
    GRADLE,
    BAZEL,
    UNKNOWN
}
