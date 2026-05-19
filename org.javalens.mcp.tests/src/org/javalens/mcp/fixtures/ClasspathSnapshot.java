package org.javalens.mcp.fixtures;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Read-only snapshot of an IJavaProject's classpath, captured at a point in time.
 * Duplicate of {@code org.javalens.core.fixtures.ClasspathSnapshot} kept in sync because
 * cross-fragment package sharing in this codebase requires bundle restructuring.
 *
 * <p>Provides assertion-friendly accessors so tests can verify classpath shape and content
 * without poking JDT internals directly. A snapshot reflects only what {@link IJavaProject#getRawClasspath()}
 * exposes; it does not resolve container entries (JRE container, etc.) into their constituent jars.
 */
public final class ClasspathSnapshot {

    private final List<Path> sourceFolders;
    private final List<Path> libraries;
    private final List<String> containers;
    private final List<String> projectRefs;
    private final String compilerSource;
    private final String compilerCompliance;

    private ClasspathSnapshot(
            List<Path> sourceFolders,
            List<Path> libraries,
            List<String> containers,
            List<String> projectRefs,
            String compilerSource,
            String compilerCompliance) {
        this.sourceFolders = Collections.unmodifiableList(sourceFolders);
        this.libraries = Collections.unmodifiableList(libraries);
        this.containers = Collections.unmodifiableList(containers);
        this.projectRefs = Collections.unmodifiableList(projectRefs);
        this.compilerSource = compilerSource;
        this.compilerCompliance = compilerCompliance;
    }

    public static ClasspathSnapshot capture(IJavaProject project) throws JavaModelException {
        List<Path> sourceFolders = new ArrayList<>();
        List<Path> libraries = new ArrayList<>();
        List<String> containers = new ArrayList<>();
        List<String> projectRefs = new ArrayList<>();

        IClasspathEntry[] entries = project.getRawClasspath();
        for (IClasspathEntry entry : entries) {
            switch (entry.getEntryKind()) {
                case IClasspathEntry.CPE_SOURCE -> {
                    Path resolved = resolveWorkspacePath(entry.getPath());
                    if (resolved != null) sourceFolders.add(resolved);
                }
                case IClasspathEntry.CPE_LIBRARY -> {
                    Path resolved = resolveLibraryPath(entry.getPath());
                    if (resolved != null) libraries.add(resolved);
                }
                case IClasspathEntry.CPE_CONTAINER -> containers.add(entry.getPath().toString());
                case IClasspathEntry.CPE_PROJECT -> projectRefs.add(entry.getPath().toString());
                default -> { /* CPE_VARIABLE not currently used */ }
            }
        }

        String source = project.getOption(JavaCore.COMPILER_SOURCE, true);
        String compliance = project.getOption(JavaCore.COMPILER_COMPLIANCE, true);

        return new ClasspathSnapshot(sourceFolders, libraries, containers, projectRefs, source, compliance);
    }

    public List<Path> sourceFolders() { return sourceFolders; }
    public List<Path> libraries() { return libraries; }
    public List<String> containers() { return containers; }
    public List<String> projectRefs() { return projectRefs; }
    public String compilerSource() { return compilerSource; }
    public String compilerCompliance() { return compilerCompliance; }

    public boolean hasLibraryMatching(String regex) {
        Pattern p = Pattern.compile(regex);
        return libraries.stream().anyMatch(path -> p.matcher(path.toString().replace('\\', '/')).matches());
    }

    public boolean hasSourceFolderMatching(String regex) {
        Pattern p = Pattern.compile(regex);
        return sourceFolders.stream().anyMatch(path -> p.matcher(path.toString().replace('\\', '/')).matches());
    }

    public long libraryCountEndingWith(String suffix) {
        return libraries.stream()
            .filter(path -> path.toString().replace('\\', '/').endsWith(suffix))
            .count();
    }

    @Override
    public String toString() {
        return "ClasspathSnapshot{" +
            "sourceFolders=" + sourceFolders.size() +
            ", libraries=" + libraries.size() +
            ", containers=" + containers.size() +
            ", projectRefs=" + projectRefs.size() +
            ", compilerSource=" + compilerSource +
            ", compilerCompliance=" + compilerCompliance +
            '}';
    }

    private static Path resolveWorkspacePath(IPath ipath) {
        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(ipath);
        if (resource == null) return null;
        IPath location = resource.getLocation();
        if (location == null) return null;
        return Path.of(location.toOSString()).toAbsolutePath().normalize();
    }

    private static Path resolveLibraryPath(IPath ipath) {
        // Library paths are typically absolute filesystem paths. Include absolute paths
        // unconditionally — a snapshot's job is to reflect what the importer put on the
        // classpath, NOT to filter out dangling entries. Tests that care about file
        // existence assert it explicitly via Files.isRegularFile on the snapshot path; a
        // previous "drop non-existent" branch silently masked "importer added a path that
        // doesn't resolve" bugs. Mirrors the fix in core.tests ClasspathSnapshot.
        if (ipath.isAbsolute()) {
            return Path.of(ipath.toOSString()).toAbsolutePath().normalize();
        }
        return resolveWorkspacePath(ipath);
    }
}
