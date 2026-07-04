package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.javalens.core.IJdtService;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.session.ProjectRegistry;
import org.javalens.mcp.session.Session;
import org.javalens.mcp.session.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Load a Java project for analysis.
 * MUST be called before using navigation/analysis tools.
 *
 * Adapted from src/main/java/dev/javalens/tools/LoadProjectTool.java
 */
public class LoadProjectTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(LoadProjectTool.class);

    private final ProjectRegistry projectRegistry;

    /**
     * @param projectRegistry the shared registry to attach the current session (via
     *        {@link SessionContext#current()}) to once this call's load succeeds —
     *        this is what lets a different session attaching to the same path later
     *        reuse the result instead of reloading.
     */
    public LoadProjectTool(ProjectRegistry projectRegistry) {
        this.projectRegistry = projectRegistry;
    }

    @Override
    public String getName() {
        return "load_project";
    }

    @Override
    public String getDescription() {
        return """
            Load a Java project for analysis.
            MUST be called before using other analysis tools.

            USAGE: load_project(projectPath="/path/to/project")
            OUTPUT: Project structure summary including packages, source files, build system

            Supports:
            - Maven projects (pom.xml)
            - Gradle projects (build.gradle or build.gradle.kts)
            - Plain Java projects with src/ directory

            WORKFLOW:
            1. Call load_project with absolute path to project root
            2. Wait for project to load (may take a few seconds for large projects)
            3. Use health_check to verify project is loaded
            4. Begin using analysis tools (search_symbols, find_references, etc.)
            """ + syncContract();
    }

    /** Mode-matched sync contract: the description's claim must equal the behavior. */
    private static String syncContract() {
        if (org.javalens.core.sync.DiskSyncMode.fromEnvironment(System.getenv("JAVALENS_DISK_SYNC"))
                == org.javalens.core.sync.DiskSyncMode.MANUAL) {
            return """

                SYNC (manual mode): answers reflect the last load. After writing, adding,
                or deleting source files, call load_project again to refresh the model.
                """;
        }
        return """

            SYNC (strict mode): answers are always verified against the files on disk -
            no reload is needed after editing, adding, or deleting source files. Call
            load_project only on first use, when a response reports RELOAD_REQUIRED
            (a build file changed), or to rebuild everything from scratch.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("projectPath", "string", "Absolute path to the project root directory containing pom.xml or build.gradle")
            .build();
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        if (arguments == null || !arguments.has("projectPath")) {
            return ToolResponse.invalidParameter("projectPath", "Required parameter missing");
        }

        String projectPath = arguments.get("projectPath").asText();

        Session session = SessionContext.current();
        if (session == null) {
            // Every real transport binds a session before dispatching any tool call
            // (stdio binds one ambient session at startup; a future HTTP transport
            // binds one per request). Only reachable from a test that never bound one.
            return ToolResponse.internalError(new IllegalStateException(
                "No active session bound — load_project cannot store its result"));
        }

        try {
            Path path = Path.of(projectPath).toAbsolutePath().normalize();

            // Validate path
            if (!Files.exists(path)) {
                return ToolResponse.error("FILE_NOT_FOUND",
                    "Project path not found: " + projectPath,
                    "Verify the path exists and is accessible");
            }

            if (!Files.isDirectory(path)) {
                return ToolResponse.error("INVALID_PARAMETER",
                    "Project path is not a directory: " + projectPath,
                    "Provide path to project root directory");
            }

            log.info("Loading project: {}", path);

            // Capture the outgoing service BEFORE loading the new one: if loadProject()
            // below throws, the previous service must stay registered and untouched (a
            // failed reload shouldn't leave the caller with nothing usable).
            IJdtService previous = session.getJdtService();

            // Create and initialize the JDT service
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(path);

            // Register the freshly-loaded service with the shared registry and attach
            // this session to it, so a different session attaching to the same path
            // later reuses it instead of reloading (see ProjectRegistry.registerLoaded).
            projectRegistry.registerLoaded(session, service);

            // Only now, after the new service has taken over successfully, release the
            // old one's workspace project. There's no automatic sweep for this (see
            // WorkspaceManager's class Javadoc) - without this, every reload within the
            // same session would leak the previous project's workspace entry.
            if (previous != null) {
                previous.dispose();
            }

            // Build response
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("loaded", true);
            result.put("projectPath", service.getPathUtils().formatPath(path));
            result.put("buildSystem", service.getBuildSystem().name().toLowerCase());
            result.put("sourceFileCount", service.getSourceFileCount());
            result.put("packageCount", service.getPackageCount());

            // Include first 20 packages
            List<String> packages = service.getPackages();
            result.put("packages", packages.size() <= 20 ? packages : packages.subList(0, 20));

            result.put("classpathEntryCount", service.getClasspathEntryCount());
            result.put("loadedAt", service.getLoadedAt().toString());

            // Surface any warnings collected during the load (e.g., mvn subprocess failure).
            // Bug X fix: previously these failures were silently swallowed and the user saw
            // a successful response with a degraded classpath.
            List<LoadWarning> warnings = service.getWarnings();
            if (!warnings.isEmpty()) {
                List<Map<String, Object>> warningJson = new ArrayList<>();
                for (LoadWarning w : warnings) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("code", w.code());
                    entry.put("message", w.message());
                    if (w.remediation() != null) entry.put("remediation", w.remediation());
                    if (w.module() != null) entry.put("module", w.module());
                    warningJson.add(entry);
                }
                result.put("warnings", warningJson);
                log.warn("Project loaded with {} warning(s)", warnings.size());
            }

            log.info("Project loaded successfully: {} files, {} packages",
                service.getSourceFileCount(), service.getPackageCount());

            return ToolResponse.success(result);

        } catch (Exception e) {
            log.error("Failed to load project", e);
            return ToolResponse.internalError(e);
        }
    }
}
