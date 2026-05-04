package org.javalens.core.fixtures;

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
 *
 * <p>Provides assertion-friendly accessors so tests can verify classpath shape and content
 * without poking JDT internals directly. A snapshot reflects only what {@link IJavaProject#getRawClasspath()}
 * exposes; it does not resolve container entries (JRE container, etc.) into their constituent jars.
 *
 * <p>Each accessor returns an immutable view. Source folders and libraries are returned as filesystem
 * {@link Path} objects (absolute, normalized). Containers and project references are returned as
 * raw classpath path strings.
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

    /**
     * Capture a snapshot of the given project's raw classpath.
     */
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
                    // Library paths can be absolute filesystem paths or workspace-relative.
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

    /**
     * Source folder paths on the classpath (absolute, normalized).
     * For linked folders, returns the underlying linked target on the filesystem.
     */
    public List<Path> sourceFolders() {
        return sourceFolders;
    }

    /**
     * Library jar/class-folder paths on the classpath (absolute, normalized).
     */
    public List<Path> libraries() {
        return libraries;
    }

    /**
     * Raw container path strings (e.g., {@code org.eclipse.jdt.launching.JRE_CONTAINER}).
     */
    public List<String> containers() {
        return containers;
    }

    /**
     * Workspace-relative paths of project references on the classpath.
     */
    public List<String> projectRefs() {
        return projectRefs;
    }

    /**
     * Effective {@code COMPILER_SOURCE} option (e.g., "21"), inherited from workspace if not set on project.
     */
    public String compilerSource() {
        return compilerSource;
    }

    /**
     * Effective {@code COMPILER_COMPLIANCE} option, inherited from workspace if not set on project.
     */
    public String compilerCompliance() {
        return compilerCompliance;
    }

    /**
     * True if any library jar path matches the given regex (against the full path string).
     * Useful for: {@code snapshot.hasLibraryMatching(".*mockito-core-.*\\.jar")}.
     */
    public boolean hasLibraryMatching(String regex) {
        Pattern p = Pattern.compile(regex);
        return libraries.stream().anyMatch(path -> p.matcher(path.toString().replace('\\', '/')).matches());
    }

    /**
     * True if any source folder path matches the given regex.
     * Useful for: {@code snapshot.hasSourceFolderMatching(".*generated-sources/annotations.*")}.
     */
    public boolean hasSourceFolderMatching(String regex) {
        Pattern p = Pattern.compile(regex);
        return sourceFolders.stream().anyMatch(path -> p.matcher(path.toString().replace('\\', '/')).matches());
    }

    /**
     * Count of library entries whose path ends with the given suffix.
     * Useful for asserting deduplication: {@code snapshot.libraryCountEndingWith("libapp.jar") == 1}.
     */
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
        // Workspace-relative paths look like /<projectName>/<folder>.
        // Resolve via the workspace root so we get the linked-target absolute path.
        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(ipath);
        if (resource == null) return null;
        IPath location = resource.getLocation();
        if (location == null) return null;
        return Path.of(location.toOSString()).toAbsolutePath().normalize();
    }

    private static Path resolveLibraryPath(IPath ipath) {
        // Library paths are typically absolute filesystem paths. If not, resolve via workspace.
        if (ipath.isAbsolute() && ipath.toFile().exists()) {
            return Path.of(ipath.toOSString()).toAbsolutePath().normalize();
        }
        return resolveWorkspacePath(ipath);
    }
}
