package org.javalens.mcp;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.javalens.mcp.protocol.McpProtocolHandler;
import org.javalens.core.IJdtService;
import org.javalens.mcp.tools.HealthCheckTool;
import org.javalens.mcp.tools.LoadProjectTool;
import org.javalens.mcp.tools.SearchSymbolsTool;
import org.javalens.mcp.tools.GoToDefinitionTool;
import org.javalens.mcp.tools.FindReferencesTool;
import org.javalens.mcp.tools.FindImplementationsTool;
import org.javalens.mcp.tools.GetTypeHierarchyTool;
import org.javalens.mcp.tools.GetDocumentSymbolsTool;
import org.javalens.mcp.tools.GetTypeMembersTool;
import org.javalens.mcp.tools.GetClasspathInfoTool;
import org.javalens.mcp.tools.GetProjectStructureTool;
import org.javalens.mcp.tools.GetSymbolInfoTool;
import org.javalens.mcp.tools.GetTypeAtPositionTool;
import org.javalens.mcp.tools.GetMethodAtPositionTool;
import org.javalens.mcp.tools.GetFieldAtPositionTool;
import org.javalens.mcp.tools.GetHoverInfoTool;
import org.javalens.mcp.tools.GetJavadocTool;
import org.javalens.mcp.tools.GetSignatureHelpTool;
import org.javalens.mcp.tools.GetEnclosingElementTool;
import org.javalens.mcp.tools.GetSuperMethodTool;
import org.javalens.mcp.tools.GetDiagnosticsTool;
import org.javalens.mcp.tools.ValidateSyntaxTool;
import org.javalens.mcp.tools.GetCallHierarchyIncomingTool;
import org.javalens.mcp.tools.GetCallHierarchyOutgoingTool;
import org.javalens.mcp.tools.FindFieldWritesTool;
import org.javalens.mcp.tools.FindAffectedTestsTool;
import org.javalens.mcp.tools.GetHttpEndpointsTool;
import org.javalens.mcp.tools.GetJpaModelTool;
import org.javalens.mcp.tools.FindTestsTool;
import org.javalens.mcp.tools.FindUnreachableCodeTool;
import org.javalens.mcp.tools.FindUnusedCodeTool;
import org.javalens.mcp.tools.FindPossibleBugsTool;
import org.javalens.mcp.tools.RenameSymbolTool;
import org.javalens.mcp.tools.OrganizeImportsTool;
import org.javalens.mcp.tools.ExtractVariableTool;
import org.javalens.mcp.tools.ExtractMethodTool;
import org.javalens.mcp.tools.FindAnnotationUsagesTool;
import org.javalens.mcp.tools.FindTypeInstantiationsTool;
import org.javalens.mcp.tools.FindCastsTool;
import org.javalens.mcp.tools.FindInstanceofChecksTool;
import org.javalens.mcp.tools.FindThrowsDeclarationsTool;
import org.javalens.mcp.tools.FindCatchBlocksTool;
import org.javalens.mcp.tools.FindMethodReferencesTool;
import org.javalens.mcp.tools.FindTypeArgumentsTool;
import org.javalens.mcp.tools.AnalyzeFileTool;
import org.javalens.mcp.tools.AnalyzeTypeTool;
import org.javalens.mcp.tools.AnalyzeMethodTool;
import org.javalens.mcp.tools.GetTypeUsageSummaryTool;
import org.javalens.mcp.tools.ExtractConstantTool;
import org.javalens.mcp.tools.InlineVariableTool;
import org.javalens.mcp.tools.InlineMethodTool;
import org.javalens.mcp.tools.ChangeMethodSignatureTool;
import org.javalens.mcp.tools.ExtractInterfaceTool;
import org.javalens.mcp.tools.ConvertAnonymousToLambdaTool;
import org.javalens.mcp.tools.SuggestImportsTool;
import org.javalens.mcp.tools.GetQuickFixesTool;
import org.javalens.mcp.tools.ApplyQuickFixTool;
import org.javalens.mcp.tools.ApplyCleanupTool;
import org.javalens.mcp.tools.EncapsulateFieldTool;
import org.javalens.mcp.tools.PullUpTool;
import org.javalens.mcp.tools.PushDownTool;
import org.javalens.mcp.tools.ExtractSuperclassTool;
import org.javalens.mcp.tools.IntroduceParameterObjectTool;
import org.javalens.mcp.tools.MoveTypeToNewFileTool;
import org.javalens.mcp.tools.DiagnoseAndFixTool;
import org.javalens.mcp.tools.GetComplexityMetricsTool;
import org.javalens.mcp.tools.GetDependencyGraphTool;
import org.javalens.mcp.tools.FindCircularDependenciesTool;
import org.javalens.mcp.tools.AnalyzeChangeImpactTool;
import org.javalens.mcp.tools.AnalyzeControlFlowTool;
import org.javalens.mcp.tools.AnalyzeDataFlowTool;
import org.javalens.mcp.tools.GetDiRegistrationsTool;
import org.javalens.mcp.tools.FindReflectionUsageTool;
import org.javalens.mcp.tools.FindLargeClassesTool;
import org.javalens.mcp.tools.FindNamingViolationsTool;
import org.javalens.mcp.session.LoadedProject;
import org.javalens.mcp.session.ProjectRegistry;
import org.javalens.mcp.session.Session;
import org.javalens.mcp.session.SessionContext;
import org.javalens.mcp.session.SessionManager;
import org.javalens.mcp.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.javalens.core.JdtServiceImpl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * OSGi application entry point for JavaLens MCP server.
 * Reads JSON-RPC messages from stdin and writes responses to stdout.
 *
 * <p>Session isolation is handled by the JavaLensLauncher wrapper which
 * injects a unique UUID into the workspace path before OSGi starts.
 *
 * <p>Project state (the loaded {@code IJdtService}, its loading state/error)
 * lives on a {@link Session} attached to a {@link LoadedProject} in the
 * shared {@link ProjectRegistry} — not on this class — so the same wiring
 * here works whether one ambient session serves the whole process (stdio,
 * today) or many concurrent sessions each get their own (a future HTTP
 * transport). Tool code reads the ambient session via {@link SessionContext},
 * never through a field on this class.
 */
public class JavaLensApplication implements IApplication {

    private static final Logger log = LoggerFactory.getLogger(JavaLensApplication.class);

    /**
     * Stdio mode has exactly one session for the entire process lifetime —
     * unlike a real multi-session server, it must never be idle-evicted just
     * because the user paused for a while, so its timeout is effectively
     * infinite rather than SessionManager's normal (multi-tenant-tuned) default.
     */
    private static final Duration STDIO_SESSION_IDLE_TIMEOUT = Duration.ofDays(3650);

    private volatile boolean running = true;
    private ToolRegistry toolRegistry;
    private ProjectRegistry projectRegistry;
    private SessionManager sessionManager;
    private Session session;

    @Override
    public Object start(IApplicationContext context) throws Exception {
        log.info("JavaLens MCP Server starting...");

        toolRegistry = new ToolRegistry();
        projectRegistry = new ProjectRegistry();
        sessionManager = new SessionManager(toolRegistry, projectRegistry,
            STDIO_SESSION_IDLE_TIMEOUT, STDIO_SESSION_IDLE_TIMEOUT);
        session = sessionManager.create();

        registerTools();

        log.info("Registered {} tools", toolRegistry.getToolCount());

        // Auto-load project from environment variable asynchronously
        // This allows the MCP server to respond to initialize immediately
        // while the project loads in the background
        CompletableFuture.runAsync(this::autoLoadProjectFromEnv);

        // Run the main message loop (starts immediately, doesn't wait for project load)
        runMessageLoop();

        log.info("JavaLens MCP Server stopped");
        return IApplication.EXIT_OK;
    }

    /**
     * Auto-load project from JAVA_PROJECT_PATH environment variable.
     * This runs asynchronously to allow the MCP server to respond immediately.
     * The loading state is tracked and can be queried via health_check.
     */
    private void autoLoadProjectFromEnv() {
        String projectPath = System.getenv("JAVA_PROJECT_PATH");
        if (projectPath == null || projectPath.isBlank()) {
            log.debug("JAVA_PROJECT_PATH not set, waiting for load_project call");
            // Session stays unattached (NOT_LOADED)
            return;
        }

        Path path = Path.of(projectPath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            log.warn("JAVA_PROJECT_PATH points to non-existent path: {}", projectPath);
            projectRegistry.registerFailed(session, path,
                "JAVA_PROJECT_PATH points to non-existent path: " + projectPath);
            return;
        }

        if (!Files.isDirectory(path)) {
            log.warn("JAVA_PROJECT_PATH is not a directory: {}", projectPath);
            projectRegistry.registerFailed(session, path,
                "JAVA_PROJECT_PATH is not a directory: " + projectPath);
            return;
        }

        log.info("Auto-loading project from JAVA_PROJECT_PATH: {}", path);

        try {
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(path);
            projectRegistry.registerLoaded(session, service);
            log.info("Project auto-loaded successfully: {} files, {} packages",
                service.getSourceFileCount(), service.getPackageCount());
        } catch (Exception e) {
            log.error("Failed to auto-load project from JAVA_PROJECT_PATH: {}", e.getMessage(), e);
            projectRegistry.registerFailed(session, path, e.getMessage());
        }
    }

    /** Resolves the ambient session's attached project, if any. Bound once at
     * startup for stdio's single long-lived session (see {@link #start}). */
    private IJdtService currentJdtService() {
        Session s = SessionContext.current();
        return s == null ? null : s.getJdtService();
    }

    private ProjectLoadingState currentLoadingState() {
        Session s = SessionContext.current();
        return s == null ? ProjectLoadingState.NOT_LOADED : s.getLoadingState();
    }

    private String currentLoadingError() {
        Session s = SessionContext.current();
        return s == null ? null : s.getLoadingError();
    }

    private void registerTools() {
        // Register HealthCheckTool with suppliers for project status, tool count, and loading state
        toolRegistry.register(new HealthCheckTool(
            () -> currentJdtService() != null,
            () -> toolRegistry.getToolCount(),
            this::currentLoadingState,
            this::currentLoadingError,
            this::currentJdtService
        ));

        // Register LoadProjectTool - it attaches the current session (via
        // SessionContext) to the freshly-loaded project in projectRegistry, which is
        // also what makes loadingState/loadingError immediately reflect LOADED
        // (issue #30 thread: previously the state was only set by the
        // JAVA_PROJECT_PATH auto-load path, so a project loaded via the tool left
        // health_check reporting "not loaded" though every tool worked).
        toolRegistry.register(new LoadProjectTool(projectRegistry));

        // Batch 1: Core Navigation Tools
        toolRegistry.register(new SearchSymbolsTool(this::currentJdtService));
        toolRegistry.register(new GoToDefinitionTool(this::currentJdtService));
        toolRegistry.register(new FindReferencesTool(this::currentJdtService));
        toolRegistry.register(new FindImplementationsTool(this::currentJdtService));

        // Batch 2: Type Hierarchy & Document Symbols
        toolRegistry.register(new GetTypeHierarchyTool(this::currentJdtService));
        toolRegistry.register(new GetDocumentSymbolsTool(this::currentJdtService));
        toolRegistry.register(new GetTypeMembersTool(this::currentJdtService));
        toolRegistry.register(new GetClasspathInfoTool(this::currentJdtService));

        // Batch 3: Project Structure & Position Info
        toolRegistry.register(new GetProjectStructureTool(this::currentJdtService));
        toolRegistry.register(new GetSymbolInfoTool(this::currentJdtService));
        toolRegistry.register(new GetTypeAtPositionTool(this::currentJdtService));
        toolRegistry.register(new GetMethodAtPositionTool(this::currentJdtService));
        toolRegistry.register(new GetFieldAtPositionTool(this::currentJdtService));
        toolRegistry.register(new GetHoverInfoTool(this::currentJdtService));

        // Batch 4: Javadoc & Method Analysis
        toolRegistry.register(new GetJavadocTool(this::currentJdtService));
        toolRegistry.register(new GetSignatureHelpTool(this::currentJdtService));
        toolRegistry.register(new GetEnclosingElementTool(this::currentJdtService));
        toolRegistry.register(new GetSuperMethodTool(this::currentJdtService));

        // Batch 5: Diagnostics & Call Hierarchy
        toolRegistry.register(new GetDiagnosticsTool(this::currentJdtService));
        toolRegistry.register(new ValidateSyntaxTool(this::currentJdtService));
        toolRegistry.register(new GetCallHierarchyIncomingTool(this::currentJdtService));
        toolRegistry.register(new GetCallHierarchyOutgoingTool(this::currentJdtService));

        // Analysis tools
        toolRegistry.register(new FindFieldWritesTool(this::currentJdtService));
        toolRegistry.register(new FindTestsTool(this::currentJdtService));
        toolRegistry.register(new FindUnusedCodeTool(this::currentJdtService));
        toolRegistry.register(new FindUnreachableCodeTool(this::currentJdtService));
        toolRegistry.register(new FindAffectedTestsTool(this::currentJdtService));
        toolRegistry.register(new FindPossibleBugsTool(this::currentJdtService));

        // Refactoring tools
        toolRegistry.register(new RenameSymbolTool(this::currentJdtService));
        toolRegistry.register(new OrganizeImportsTool(this::currentJdtService));
        toolRegistry.register(new ExtractVariableTool(this::currentJdtService));
        toolRegistry.register(new ExtractMethodTool(this::currentJdtService));

        // Fine-grained reference search (JDT-unique capabilities)
        toolRegistry.register(new FindAnnotationUsagesTool(this::currentJdtService));
        toolRegistry.register(new FindTypeInstantiationsTool(this::currentJdtService));
        toolRegistry.register(new FindCastsTool(this::currentJdtService));
        toolRegistry.register(new FindInstanceofChecksTool(this::currentJdtService));
        toolRegistry.register(new FindThrowsDeclarationsTool(this::currentJdtService));
        toolRegistry.register(new FindCatchBlocksTool(this::currentJdtService));
        toolRegistry.register(new FindMethodReferencesTool(this::currentJdtService));
        toolRegistry.register(new FindTypeArgumentsTool(this::currentJdtService));

        // Compound analysis tools
        toolRegistry.register(new AnalyzeFileTool(this::currentJdtService));
        toolRegistry.register(new AnalyzeTypeTool(this::currentJdtService));
        toolRegistry.register(new AnalyzeMethodTool(this::currentJdtService));
        toolRegistry.register(new GetTypeUsageSummaryTool(this::currentJdtService));

        // Advanced refactoring tools
        toolRegistry.register(new ExtractConstantTool(this::currentJdtService));
        toolRegistry.register(new InlineVariableTool(this::currentJdtService));
        toolRegistry.register(new InlineMethodTool(this::currentJdtService));
        toolRegistry.register(new ChangeMethodSignatureTool(this::currentJdtService));
        toolRegistry.register(new ExtractInterfaceTool(this::currentJdtService));
        toolRegistry.register(new ConvertAnonymousToLambdaTool(this::currentJdtService));

        // Quick fix tools
        toolRegistry.register(new SuggestImportsTool(this::currentJdtService));
        toolRegistry.register(new GetQuickFixesTool(this::currentJdtService));
        toolRegistry.register(new ApplyQuickFixTool(this::currentJdtService));
        toolRegistry.register(new ApplyCleanupTool(this::currentJdtService));
        toolRegistry.register(new EncapsulateFieldTool(this::currentJdtService));
        toolRegistry.register(new PullUpTool(this::currentJdtService));
        toolRegistry.register(new PushDownTool(this::currentJdtService));
        toolRegistry.register(new ExtractSuperclassTool(this::currentJdtService));
        toolRegistry.register(new IntroduceParameterObjectTool(this::currentJdtService));
        toolRegistry.register(new MoveTypeToNewFileTool(this::currentJdtService));
        toolRegistry.register(new DiagnoseAndFixTool(this::currentJdtService));

        // Metrics tools
        toolRegistry.register(new GetComplexityMetricsTool(this::currentJdtService));
        toolRegistry.register(new GetDependencyGraphTool(this::currentJdtService));
        toolRegistry.register(new FindCircularDependenciesTool(this::currentJdtService));

        // Advanced analysis tools
        toolRegistry.register(new AnalyzeChangeImpactTool(this::currentJdtService));
        toolRegistry.register(new AnalyzeControlFlowTool(this::currentJdtService));
        toolRegistry.register(new AnalyzeDataFlowTool(this::currentJdtService));
        toolRegistry.register(new GetDiRegistrationsTool(this::currentJdtService));
        toolRegistry.register(new GetJpaModelTool(this::currentJdtService));
        toolRegistry.register(new GetHttpEndpointsTool(this::currentJdtService));
        toolRegistry.register(new FindReflectionUsageTool(this::currentJdtService));
        toolRegistry.register(new FindLargeClassesTool(this::currentJdtService));
        toolRegistry.register(new FindNamingViolationsTool(this::currentJdtService));
    }

    private void runMessageLoop() {
        // Bound once for the life of this thread: stdio has exactly one session and
        // exactly one thread reading it, so there's nothing to rebind per-message. A
        // future per-request HTTP transport would bind/clear per request instead.
        SessionContext.bind(session);
        McpProtocolHandler protocolHandler = session.getProtocolHandler();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {

            log.debug("Entering message loop");

            while (running) {
                String line = reader.readLine();
                if (line == null) {
                    log.debug("End of input stream, exiting");
                    break;
                }

                if (line.isBlank()) {
                    continue;
                }

                log.debug("Received: {}", line);

                try {
                    String response = protocolHandler.processMessage(line);
                    if (response != null) {
                        writer.println(response);
                        writer.flush();
                        log.debug("Sent: {}", response);
                    }
                } catch (Exception e) {
                    log.error("Error processing message", e);
                }
            }
        } catch (Exception e) {
            log.error("Error in message loop", e);
        } finally {
            SessionContext.clear();
        }
    }

    @Override
    public void stop() {
        log.info("Stop requested");
        running = false;
    }
}
