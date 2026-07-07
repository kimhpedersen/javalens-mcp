package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.session.ProjectRegistry;
import org.javalens.mcp.session.Session;
import org.javalens.mcp.session.SessionContext;
import org.javalens.mcp.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 4 (multiplexing.md): {@link AbstractTool#execute} must route through the
 * attached {@link org.javalens.mcp.session.LoadedProject}'s {@code runExclusive}
 * lock, so overlapping requests from different sessions sharing one project
 * serialize, while requests against distinct projects run fully in parallel.
 * {@link org.javalens.mcp.session.LoadedProjectTest} already pins the lock's own
 * behavior in isolation; this class pins that {@code execute()} actually uses it.
 */
class AbstractToolConcurrencyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearSessionContext() {
        SessionContext.clear();
    }

    @Test
    @DisplayName("execute() serializes overlapping calls from different sessions attached to the same project")
    void execute_sameProject_serializesAcrossSessions() throws Exception {
        try (ProjectRegistry registry = new ProjectRegistry();
             SessionManager sessionManager = new SessionManager(new ToolRegistry(), registry)) {

            Session s1 = sessionManager.create();
            Session s2 = sessionManager.create();
            FakeJdtService service = new FakeJdtService(Path.of("/tmp/abstract-tool-concurrency-shared"));
            registry.registerLoaded(s1, service);
            registry.attach(s2, service.getProjectRoot());

            AtomicInteger concurrentInSection = new AtomicInteger(0);
            AtomicInteger maxObservedConcurrency = new AtomicInteger(0);
            SlowTestTool tool1 = new SlowTestTool(s1::getJdtService, concurrentInSection, maxObservedConcurrency);
            SlowTestTool tool2 = new SlowTestTool(s2::getJdtService, concurrentInSection, maxObservedConcurrency);

            runConcurrently(s1, tool1, s2, tool2);

            assertEquals(1, maxObservedConcurrency.get(),
                "two sessions attached to the same project must never execute inside the locked section at once");
        }
    }

    @Test
    @DisplayName("execute() runs calls against two different projects fully in parallel (no shared lock)")
    void execute_differentProjects_runConcurrently() throws Exception {
        try (ProjectRegistry registry = new ProjectRegistry();
             SessionManager sessionManager = new SessionManager(new ToolRegistry(), registry)) {

            Session s1 = sessionManager.create();
            Session s2 = sessionManager.create();
            registry.registerLoaded(s1, new FakeJdtService(Path.of("/tmp/abstract-tool-concurrency-a")));
            registry.registerLoaded(s2, new FakeJdtService(Path.of("/tmp/abstract-tool-concurrency-b")));

            AtomicInteger concurrentInSection = new AtomicInteger(0);
            AtomicInteger maxObservedConcurrency = new AtomicInteger(0);
            SlowTestTool tool1 = new SlowTestTool(s1::getJdtService, concurrentInSection, maxObservedConcurrency);
            SlowTestTool tool2 = new SlowTestTool(s2::getJdtService, concurrentInSection, maxObservedConcurrency);

            runConcurrently(s1, tool1, s2, tool2);

            assertEquals(2, maxObservedConcurrency.get(),
                "sessions on distinct projects must not serialize against each other");
        }
    }

    /** Runs tool1 (bound to session1) and tool2 (bound to session2) on separate threads,
     * released at the same instant so their locked sections have maximum overlap opportunity. */
    private void runConcurrently(Session session1, SlowTestTool tool1, Session session2, SlowTestTool tool2)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<?> f1 = pool.submit(() -> boundExecute(session1, tool1, ready, go));
            Future<?> f2 = pool.submit(() -> boundExecute(session2, tool2, ready, go));
            ready.await();
            go.countDown();
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private ToolResponse boundExecute(Session session, SlowTestTool tool, CountDownLatch ready, CountDownLatch go) {
        SessionContext.bind(session);
        try {
            ready.countDown();
            go.await();
            return tool.execute(objectMapper.createObjectNode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            SessionContext.clear();
        }
    }

    /** AbstractTool whose executeWithService sleeps briefly while tracking observed concurrency. */
    private static class SlowTestTool extends AbstractTool {
        private final AtomicInteger concurrentInSection;
        private final AtomicInteger maxObservedConcurrency;

        SlowTestTool(java.util.function.Supplier<IJdtService> supplier,
                     AtomicInteger concurrentInSection, AtomicInteger maxObservedConcurrency) {
            super(supplier);
            this.concurrentInSection = concurrentInSection;
            this.maxObservedConcurrency = maxObservedConcurrency;
        }

        @Override public String getName() { return "slow_test_tool"; }
        @Override public String getDescription() { return "test"; }
        @Override public java.util.Map<String, Object> getInputSchema() { return java.util.Map.of(); }

        @Override
        protected ToolResponse executeWithService(IJdtService service, com.fasterxml.jackson.databind.JsonNode arguments) {
            int current = concurrentInSection.incrementAndGet();
            maxObservedConcurrency.updateAndGet(prev -> Math.max(prev, current));
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrentInSection.decrementAndGet();
            }
            return ToolResponse.success(java.util.Map.of("ok", true));
        }
    }

    /** Bare IJdtService: non-null project root (identity for the registry key), ensureFresh() is a no-op. */
    private static class FakeJdtService implements IJdtService {
        private final Path projectRoot;

        FakeJdtService(Path projectRoot) {
            this.projectRoot = projectRoot;
        }

        @Override public org.javalens.core.IPathUtils getPathUtils() { return null; }
        @Override public List<org.javalens.core.project.model.LoadWarning> getWarnings() { return List.of(); }
        @Override public Path getProjectRoot() { return projectRoot; }
        @Override public int getTimeoutSeconds() { return 30; }
        @Override public <T> T executeWithTimeout(java.util.concurrent.Callable<T> op, String name) { return null; }
        @Override public org.eclipse.jdt.core.IJavaProject getJavaProject() { return null; }
        @Override public org.javalens.core.search.SearchService getSearchService() { return null; }
        @Override public org.javalens.core.graph.ProjectGraphService getProjectGraphService() { return null; }
        @Override public List<Path> ensureFresh() { return List.of(); }
        @Override public org.javalens.core.sync.DiskSyncMode getDiskSyncMode() { return org.javalens.core.sync.DiskSyncMode.STRICT; }
        @Override public org.eclipse.jdt.core.ICompilationUnit getCompilationUnit(Path p) { return null; }
        @Override public org.eclipse.jdt.core.IJavaElement getElementAtPosition(Path p, int l, int c) { return null; }
        @Override public org.eclipse.jdt.core.IType getTypeAtPosition(Path p, int l, int c) { return null; }
        @Override public org.eclipse.jdt.core.IType findType(String n) { return null; }
        @Override public String getContextLine(org.eclipse.jdt.core.ICompilationUnit cu, int o) { return ""; }
        @Override public int getOffset(org.eclipse.jdt.core.ICompilationUnit cu, int l, int c) { return 0; }
        @Override public int getLineNumber(org.eclipse.jdt.core.ICompilationUnit cu, int o) { return 0; }
        @Override public int getColumnNumber(org.eclipse.jdt.core.ICompilationUnit cu, int o) { return 0; }
        @Override public List<Path> getAllJavaFiles() { return List.of(); }
        @Override public void dispose() { }
    }
}
